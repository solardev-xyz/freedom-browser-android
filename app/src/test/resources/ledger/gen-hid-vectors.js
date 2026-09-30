// Regenerates hid-framing-vectors.json (#319): the USB HID packets
// Ledger's own @ledgerhq/devices hid-framing (what hw-transport-node-hid,
// desktop's transport, uses) makes for APDUs of every length around a
// packet boundary, and the answers it reads back from packets, plus the
// model identifyUSBProductId names for each product id. LedgerHidFramingTest
// checks LedgerHidFraming against them byte for byte.
//
//   npm install @ledgerhq/devices@8.4.4
//   node gen-hid-vectors.js > hid-framing-vectors.json
const framing = require("@ledgerhq/devices/lib/hid-framing").default(0x0101, 64);
const { identifyUSBProductId } = require("@ledgerhq/devices");

const bytes = (n, seed) => Buffer.from(Array.from({ length: n }, (_, i) => (i * 31 + seed) & 0xff));
const lengths = [0, 1, 5, 56, 57, 58, 59, 60, 115, 116, 117, 118, 175, 176, 260, 1000];
const packets = lengths.map((n) => {
  const apdu = bytes(n, n);
  return { apdu: apdu.toString("hex"), packets: framing.makeBlocks(apdu).map((b) => b.toString("hex")) };
});
// An answer is read back from the same packets the device sends, padding and all.
const answers = lengths.filter((n) => n >= 2).map((n) => {
  const answer = bytes(n, n + 7);
  let acc = null;
  const blocks = framing.makeBlocks(answer).map((b) => b.slice(0, 64));
  for (const b of blocks) acc = framing.reduceResponse(acc, b);
  return { packets: blocks.map((b) => b.toString("hex")), answer: framing.getReducedResult(acc).toString("hex") };
});
const ids = [0x0000, 0x0001, 0x0004, 0x0005, 0x0006, 0x0007, 0x0011, 0x1011, 0x1015, 0x4011, 0x4015, 0x5000, 0x5011, 0x5015, 0x6011, 0x7011, 0x2011, 0x8011, 0xff00];
const models = Object.fromEntries(ids.map((id) => [id.toString(16).padStart(4, "0"), identifyUSBProductId(id)?.productName ?? null]));
console.log(JSON.stringify({ packets, answers, models }, null, 1));
