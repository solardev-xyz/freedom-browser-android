// Regenerates hw-app-eth-vectors.json (#142): the APDUs and BLE frames
// Ledger's own @ledgerhq/hw-app-eth builds for a set of transactions,
// messages and typed data, recorded through a transport that answers
// 0x9000 to everything. LedgerProtocolTest checks LedgerApdus and
// LedgerBleFraming against them byte for byte.
//
//   npm install @ledgerhq/hw-app-eth @ledgerhq/hw-transport @ledgerhq/hw-transport-web-ble ethers@6
//   node gen-vectors.js > hw-app-eth-vectors.json
const Transport = require("@ledgerhq/hw-transport").default;
const Eth = require("@ledgerhq/hw-app-eth").default;
const { sendAPDU } = require("./node_modules/@ledgerhq/hw-transport-web-ble/lib/ble/sendAPDU");
const { Transaction } = require("ethers");
const { lastValueFrom, toArray } = require("rxjs");

class Rec extends Transport {
  constructor() { super(); this.log = []; }
  async exchange(apdu) {
    this.log.push(apdu.toString("hex"));
    const ins = apdu[1];
    if (apdu[0] === 0xb0) return Buffer.concat([Buffer.from([1, 8]), Buffer.from("Ethereum"), Buffer.from([6]), Buffer.from("1.22.3"), Buffer.from([1, 0]), Buffer.from("9000", "hex")]);
    if (ins === 0x06) return Buffer.from("00011603" + "9000", "hex");
    if (ins === 0x04 || ins === 0x08 || ins === 0x0c) {
      return Buffer.concat([Buffer.alloc(65, 1), Buffer.from("9000", "hex")]);
    }
    return Buffer.from("9000", "hex");
  }
}
const cfg = { nftExplorerBaseURL: null, pluginBaseURL: null, extraPlugins: null, cryptoassetsBaseURL: null, calServiceURL: null };
const path = "44'/60'/0'/0/0";
(async () => {
  const out = {};
  const txs = {
    legacy: Transaction.from({ type: 0, chainId: 100, nonce: 7, gasPrice: 2000000000n, gasLimit: 21000, to: "0x1111111111111111111111111111111111111111", value: 10n ** 15n, data: "0x" }),
    legacyBig: Transaction.from({ type: 0, chainId: 100, nonce: 7, gasPrice: 2000000000n, gasLimit: 90000, to: "0x1111111111111111111111111111111111111111", value: 0n, data: "0x" + "ab".repeat(300) }),
    eip1559: Transaction.from({ type: 2, chainId: 100, nonce: 3, maxFeePerGas: 3000000000n, maxPriorityFeePerGas: 1000000000n, gasLimit: 65000, to: "0x2222222222222222222222222222222222222222", value: 0n, data: "0xa9059cbb" + "00".repeat(12) + "33".repeat(20) + "00".repeat(31) + "05" }),
  };
  // legacy tx whose chunk boundary lands in the EIP-155 tail
  for (let n = 100; n < 160; n++) {
    const t = Transaction.from({ type: 0, chainId: 100, nonce: 1, gasPrice: 1n, gasLimit: 21000, to: "0x1111111111111111111111111111111111111111", value: 0n, data: "0x" + "cd".repeat(n) });
    txs["legacyEdge" + n] = t;
  }
  for (const [k, t] of Object.entries(txs)) {
    const r = new Rec(); const eth = new Eth(r, undefined, cfg);
    // The canned signature's v doesn't parse for every tx type; the APDUs are recorded by then.
    try { await eth.signTransaction(path, t.unsignedSerialized.slice(2), null); } catch (e) {}
    out["tx_" + k] = { payload: t.unsignedSerialized.slice(2), apdus: r.log };
  }
  for (const [k, m] of Object.entries({ short: "Hello Ledger", long: "x".repeat(400) })) {
    const r = new Rec(); const eth = new Eth(r, undefined, cfg);
    await eth.signPersonalMessage(path, Buffer.from(m).toString("hex"));
    out["personal_" + k] = { message: Buffer.from(m).toString("hex"), apdus: r.log };
  }
  const typed = {
    types: {
      EIP712Domain: [{ name: "name", type: "string" }, { name: "version", type: "string" }, { name: "chainId", type: "uint256" }, { name: "verifyingContract", type: "address" }],
      Person: [{ name: "name", type: "string" }, { name: "wallet", type: "address" }],
      Mail: [{ name: "from", type: "Person" }, { name: "to", type: "Person[]" }, { name: "contents", type: "string" }, { name: "n", type: "int16" }, { name: "flag", type: "bool" }, { name: "b4", type: "bytes4" }, { name: "blob", type: "bytes" }, { name: "grid", type: "uint8[2][]" }],
    },
    primaryType: "Mail",
    domain: { name: "Ether Mail", version: "1", chainId: 100, verifyingContract: "0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC" },
    message: { from: { name: "Cow", wallet: "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826" }, to: [{ name: "Bob", wallet: "0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB" }], contents: "Hello, Bob!", n: -5, flag: true, b4: "0x01020304", blob: "0x" + "ee".repeat(300), grid: [[1, 2], [3, 4], [5, 6]] },
  };
  {
    const r = new Rec(); const eth = new Eth(r, undefined, cfg);
    await eth.signEIP712Message(path, typed, false);
    out.eip712 = { typed, apdus: r.log };
  }
  // BLE frames
  const apdu = Buffer.from(out.tx_legacyBig.apdus[0], "hex");
  for (const mtu of [20, 23, 153]) {
    const frames = [];
    await lastValueFrom(sendAPDU(async (b) => { frames.push(b.toString("hex")); }, apdu, mtu).pipe(toArray())).catch(()=>{});
    out["ble_" + mtu] = { apdu: apdu.toString("hex"), frames };
  }
  console.log(JSON.stringify(out, null, 1));
})();
