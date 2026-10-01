// EMV field 55 (ICC data) decoder: BER-TLV parsing plus a dictionary of tag names, descriptions and
// bit-level breakdowns (EMV Book 3 Annex A/C, Book 4 Annex A, Visa VIS for the CVR).
// Display only: PAN and track-2 equivalent data (5A, 57) are masked.

const hexToBytes = h => (h.match(/../g) || []).map(b => parseInt(b, 16))
const hx = bytes => bytes.map(b => b.toString(16).toUpperCase().padStart(2, '0')).join('')
const bin = b => b.toString(2).padStart(8, '0').replace(/(.{4})/, '$1 ')
const bcd = bytes => hx(bytes)
const ascii = bytes => bytes.map(b => (b >= 32 && b < 127 ? String.fromCharCode(b) : '.')).join('')

/** Parses BER-TLV hex into [{ tag, length, value (bytes), children? }]. Throws on malformed data. */
export function parseTlv (hex) {
  const clean = (hex || '').replace(/[\s:]/g, '').toUpperCase()
  if (!/^([0-9A-F]{2})*$/.test(clean)) throw new Error('Not an even-length hex string')
  return parseBytes(hexToBytes(clean))
}

function parseBytes (b) {
  const out = []
  let i = 0
  while (i < b.length) {
    if (b[i] === 0x00 || b[i] === 0xFF) { i++; continue }       // padding between objects
    const start = i
    let tag = [b[i++]]
    if ((tag[0] & 0x1F) === 0x1F) {
      do {
        if (i >= b.length) throw new Error(`Tag truncated at byte ${start}`)
        tag.push(b[i])
      } while (b[i++] & 0x80)
    }
    if (i >= b.length) throw new Error(`Length missing for tag ${hx(tag)}`)
    let len = b[i++]
    if (len & 0x80) {
      const n = len & 0x7F
      if (n < 1 || n > 3 || i + n > b.length) throw new Error(`Bad length for tag ${hx(tag)}`)
      len = 0
      for (let k = 0; k < n; k++) len = (len << 8) | b[i++]
    }
    if (i + len > b.length) throw new Error(`Tag ${hx(tag)} says ${len} bytes, only ${b.length - i} left`)
    const value = b.slice(i, i + len)
    i += len
    const node = { tag: hx(tag), length: len, value }
    if (tag[0] & 0x20) {
      try { node.children = parseBytes(value) } catch { /* show as primitive */ }
    }
    out.push(node)
  }
  return out
}

// ---- bit maps: per byte, entries [mask, text] for single bits or { mask, text, values } for fields

function bitsFor (bytes, layout) {
  return bytes.map((byte, n) => {
    const def = layout[n] || []
    const flags = def.map(d => {
      if (Array.isArray(d)) {
        const [mask, text] = d
        return { bit: bitName(mask), text, set: (byte & mask) !== 0 }
      }
      const shift = Math.log2(d.mask & -d.mask)
      const v = (byte & d.mask) >> shift
      return { bit: bitName(d.mask), text: d.text, set: true, value: d.values?.[v] ?? `value ${v}` }
    })
    return { index: n + 1, hex: hx([byte]), bin: bin(byte), flags }
  })
}

function bitName (mask) {
  const bits = []
  for (let k = 7; k >= 0; k--) if (mask & (1 << k)) bits.push(k + 1)
  return bits.length > 1 ? `b${bits[0]}-b${bits[bits.length - 1]}` : `b${bits[0]}`
}

const TVR = [
  [[0x80, 'Offline data authentication was not performed'], [0x40, 'SDA failed'], [0x20, 'ICC data missing'],
    [0x10, 'Card appears on terminal exception file'], [0x08, 'DDA failed'], [0x04, 'CDA failed'], [0x02, 'SDA selected']],
  [[0x80, 'ICC and terminal have different application versions'], [0x40, 'Expired application'],
    [0x20, 'Application not yet effective'], [0x10, 'Requested service not allowed for card product'], [0x08, 'New card']],
  [[0x80, 'Cardholder verification was not successful'], [0x40, 'Unrecognised CVM'], [0x20, 'PIN try limit exceeded'],
    [0x10, 'PIN entry required and PIN pad not present or not working'],
    [0x08, 'PIN entry required, PIN pad present, but PIN was not entered'], [0x04, 'Online PIN entered']],
  [[0x80, 'Transaction exceeds floor limit'], [0x40, 'Lower consecutive offline limit exceeded'],
    [0x20, 'Upper consecutive offline limit exceeded'], [0x10, 'Transaction selected randomly for online processing'],
    [0x08, 'Merchant forced transaction online']],
  [[0x80, 'Default TDOL used'], [0x40, 'Issuer authentication failed'],
    [0x20, 'Script processing failed before final GENERATE AC'], [0x10, 'Script processing failed after final GENERATE AC'],
    [0x08, 'Relay resistance threshold exceeded'], [0x04, 'Relay resistance time limits exceeded'],
    { mask: 0x03, text: 'Relay resistance', values: { 0: 'Not supported', 1: 'Not performed', 2: 'Performed', 3: 'RFU' } }]
]

const TSI = [
  [[0x80, 'Offline data authentication was performed'], [0x40, 'Cardholder verification was performed'],
    [0x20, 'Card risk management was performed'], [0x10, 'Issuer authentication was performed'],
    [0x08, 'Terminal risk management was performed'], [0x04, 'Script processing was performed']],
  []
]

const AIP = [
  [[0x40, 'SDA supported'], [0x20, 'DDA supported'], [0x10, 'Cardholder verification is supported'],
    [0x08, 'Terminal risk management is to be performed'], [0x04, 'Issuer authentication is supported'],
    [0x02, 'On-device cardholder verification supported (contactless)'], [0x01, 'CDA supported']],
  [[0x80, 'Reserved for contactless specifications (e.g. EMV mode supported)']]
]

const TERM_CAPS = [
  [[0x80, 'Manual key entry'], [0x40, 'Magnetic stripe'], [0x20, 'IC with contacts']],
  [[0x80, 'Plaintext PIN for ICC verification'], [0x40, 'Enciphered PIN for online verification'],
    [0x20, 'Signature (paper)'], [0x10, 'Enciphered PIN for offline verification'], [0x08, 'No CVM required']],
  [[0x80, 'SDA'], [0x40, 'DDA'], [0x20, 'Card capture'], [0x08, 'CDA']]
]

const AUC = [
  [[0x80, 'Valid for domestic cash transactions'], [0x40, 'Valid for international cash transactions'],
    [0x20, 'Valid for domestic goods'], [0x10, 'Valid for international goods'],
    [0x08, 'Valid for domestic services'], [0x04, 'Valid for international services'],
    [0x02, 'Valid at ATMs'], [0x01, 'Valid at terminals other than ATMs']],
  [[0x80, 'Domestic cashback allowed'], [0x40, 'International cashback allowed']]
]

// Visa VIS Card Verification Results, bytes 2-4 of the CVR (byte 1 is its length)
const VISA_CVR = [
  [{ mask: 0xC0, text: 'Second GENERATE AC returned', values: { 0: 'AAC', 1: 'TC', 2: 'Not requested', 3: 'RFU' } },
    { mask: 0x30, text: 'First GENERATE AC returned', values: { 0: 'AAC', 1: 'TC', 2: 'ARQC', 3: 'RFU' } },
    [0x08, 'Issuer authentication performed and failed'], [0x04, 'Offline PIN verification performed'],
    [0x02, 'Offline PIN verification failed'], [0x01, 'Unable to go online']],
  [[0x80, 'Last online transaction not completed'], [0x40, 'PIN try limit exceeded'],
    [0x20, 'Exceeded velocity checking counters'], [0x10, 'New card'],
    [0x08, 'Issuer authentication failure on last online transaction'],
    [0x04, 'Issuer authentication not performed after online authorization'],
    [0x02, 'Application blocked by card because PIN try limit exceeded'],
    [0x01, 'Offline static data authentication failed on last transaction (declined offline)']],
  [{ mask: 0xF0, text: 'Issuer script commands processed', values: Object.fromEntries([...Array(16).keys()].map(k => [k, String(k)])) },
    [0x08, 'Issuer script processing failed'],
    [0x04, 'Offline dynamic data authentication failed on last transaction (declined offline)'],
    [0x02, 'Offline dynamic data authentication performed']]
]

const CVM_CODES = {
  0x00: 'Fail CVM processing', 0x01: 'Plaintext PIN verified by ICC', 0x02: 'Enciphered PIN verified online',
  0x03: 'Plaintext PIN verified by ICC and signature', 0x04: 'Enciphered PIN verified by ICC',
  0x05: 'Enciphered PIN verified by ICC and signature', 0x1E: 'Signature (paper)', 0x1F: 'No CVM required',
  0x3F: 'No CVM performed'
}
const CVM_CONDITIONS = {
  0x00: 'Always', 0x01: 'If unattended cash', 0x02: 'If not unattended cash, not manual cash and not purchase with cashback',
  0x03: 'If terminal supports the CVM', 0x04: 'If manual cash', 0x05: 'If purchase with cashback',
  0x06: 'If in application currency and under X', 0x07: 'If in application currency and over X',
  0x08: 'If in application currency and under Y', 0x09: 'If in application currency and over Y'
}
const CVM_RESULTS = { 0x00: 'Unknown (e.g. online PIN: the issuer verifies it)', 0x01: 'Failed', 0x02: 'Successful' }

const TXN_TYPES = {
  '00': 'Goods and services', '01': 'Cash withdrawal', '09': 'Purchase with cashback', '20': 'Refund',
  '30': 'Balance inquiry (available funds)', '31': 'Balance inquiry', '92': 'PIN change (proprietary)'
}
const TERMINAL_OPERATOR = { 1: 'Financial institution', 2: 'Merchant', 3: 'Cardholder' }
const TERMINAL_ENV = {
  1: 'Attended, online only', 2: 'Attended, offline with online capability', 3: 'Attended, offline only',
  4: 'Unattended, online only', 5: 'Unattended, offline with online capability', 6: 'Unattended, offline only'
}
const COUNTRIES = { '818': 'Egypt', '840': 'United States', '784': 'United Arab Emirates', '682': 'Saudi Arabia',
  '826': 'United Kingdom', '414': 'Kuwait', '634': 'Qatar', '048': 'Bahrain', '512': 'Oman', '400': 'Jordan', '276': 'Germany', '250': 'France' }
const CURRENCIES = { '818': 'EGP', '840': 'USD', '978': 'EUR', '784': 'AED', '682': 'SAR', '826': 'GBP',
  '414': 'KWD', '634': 'QAR', '048': 'BHD', '512': 'OMR', '400': 'JOD' }
const RIDS = { A000000003: 'Visa', A000000004: 'Mastercard', A000000025: 'American Express', A000000065: 'JCB',
  A000000152: 'Discover', A000000333: 'UnionPay', A000000277: 'Interac' }
const ARC = { '00': 'Approved', '01': 'Refer to issuer', '02': 'Refer to issuer (special)', '05': 'Declined (do not honour)',
  '51': 'Declined, insufficient funds', '55': 'Declined, incorrect PIN', Y1: 'Offline approved', Z1: 'Offline declined',
  Y3: 'Unable to go online, approved', Z3: 'Unable to go online, declined' }
const CVN = { 0x0A: 'CVN 10', 0x11: 'CVN 17', 0x12: 'CVN 18', 0x22: 'CVN 22' }

const masked = digits => digits.length > 10 ? `${digits.slice(0, 6)}${'*'.repeat(digits.length - 10)}${digits.slice(-4)}` : '****'
const amount = v => {
  const n = bcd(v).replace(/^0+(?=\d)/, '')
  return `${n} (minor units)`
}
const ymd = v => { const s = bcd(v); return `20${s.slice(0, 2)}-${s.slice(2, 4)}-${s.slice(4, 6)}` }
const flagCount = bits => bits.reduce((n, b) => n + b.flags.filter(f => f.set && f.value === undefined).length, 0)

// ---- dictionary: name, description, and an optional decode(value) -> { summary, bits?, details?, warn? }

const TAGS = {
  '4F': { name: 'Application Identifier (AID), card', desc: 'Identifies the application on the card: RID (5 bytes, the scheme) + PIX (product).' },
  50: { name: 'Application Label', desc: 'Mnemonic of the application, shown to the cardholder.', decode: v => ({ summary: ascii(v) }) },
  57: { name: 'Track 2 Equivalent Data', desc: 'Track 2 as held on the chip. Sensitive: masked here.', decode: v => ({ summary: masked(bcd(v).split('D')[0]) + ' D ****' }) },
  '5A': { name: 'Application PAN', desc: 'Card number held on the chip. Sensitive: masked here.', decode: v => ({ summary: masked(bcd(v).replace(/F+$/, '')) }) },
  '5F20': { name: 'Cardholder Name', desc: 'Name from the chip.', decode: v => ({ summary: ascii(v).trim() }) },
  '5F24': { name: 'Application Expiration Date', desc: 'YYMMDD after which the application expires.', decode: v => ({ summary: ymd(v) }) },
  '5F25': { name: 'Application Effective Date', desc: 'YYMMDD from which the application may be used.', decode: v => ({ summary: ymd(v) }) },
  '5F28': { name: 'Issuer Country Code', desc: 'ISO 3166 numeric country of the issuer.', decode: v => country(v) },
  '5F2A': { name: 'Transaction Currency Code', desc: 'ISO 4217 numeric currency of the transaction amount.', decode: v => currency(v) },
  '5F34': { name: 'PAN Sequence Number (PSN)', desc: 'Distinguishes cards with the same PAN (renewals, replacements). Part of the card key derivation.', decode: v => ({ summary: bcd(v) }) },
  82: { name: 'Application Interchange Profile (AIP)', desc: 'What the card supports in this transaction: offline authentication methods, cardholder verification, risk management, issuer authentication.', decode: v => { const bits = bitsFor(v, AIP); return { summary: listSet(bits), bits } } },
  84: { name: 'Dedicated File (DF) Name / AID', desc: 'AID of the application selected by the terminal: RID (scheme) + PIX (product).', decode: v => aid(v) },
  '8A': { name: 'Authorisation Response Code (ARC)', desc: 'Issuer decision as passed to the card.', decode: v => { const c = ascii(v); return { summary: `${c} · ${ARC[c] || 'scheme specific'}` } } },
  '8E': { name: 'CVM List', desc: 'Cardholder verification methods the issuer prefers, in order, with their conditions.' },
  91: { name: 'Issuer Authentication Data', desc: 'Sent by the issuer in the response. ARPC method 1: ARPC (8 bytes) + ARC (2 bytes). The card checks the ARPC to prove the answer came from its issuer.', decode: v => issuerAuth(v) },
  95: { name: 'Terminal Verification Results (TVR)', desc: 'The terminal\'s record of every check it ran. Each set bit is a check that failed or a condition that pushed the transaction online. All zeros = nothing to report.', decode: v => { const bits = bitsFor(v, TVR); const n = flagCount(bits); return { summary: n ? `${n} condition${n > 1 ? 's' : ''} set` : 'All checks passed (no flags set)', bits, warn: n > 0 } } },
  '9A': { name: 'Transaction Date', desc: 'Local date at the terminal, YYMMDD.', decode: v => ({ summary: ymd(v) }) },
  '9B': { name: 'Transaction Status Information (TSI)', desc: 'Which EMV functions the terminal performed.', decode: v => { const bits = bitsFor(v, TSI); return { summary: listSet(bits), bits } } },
  '9C': { name: 'Transaction Type', desc: 'First two digits of the ISO 8583 processing code.', decode: v => { const t = bcd(v); return { summary: `${t} · ${TXN_TYPES[t] || 'other'}` } } },
  '9F02': { name: 'Amount, Authorised (Numeric)', desc: 'Transaction amount, in the minor units of 5F2A.', decode: v => ({ summary: amount(v) }) },
  '9F03': { name: 'Amount, Other (Numeric)', desc: 'Secondary amount, e.g. cashback.', decode: v => ({ summary: amount(v) }) },
  '9F06': { name: 'Application Identifier (AID), terminal', desc: 'AID as configured on the terminal.', decode: v => aid(v) },
  '9F07': { name: 'Application Usage Control (AUC)', desc: 'Issuer restrictions on where and for what the application may be used.', decode: v => { const bits = bitsFor(v, AUC); return { summary: listSet(bits, 3), bits } } },
  '9F09': { name: 'Application Version Number, terminal', desc: 'Version of the scheme application the terminal implements.', decode: v => ({ summary: hx(v) }) },
  '9F0D': { name: 'Issuer Action Code, Default', desc: 'TVR bits for which the issuer wants a decline if the terminal cannot go online.', decode: v => ({ summary: hx(v), bits: bitsFor(v, TVR) }) },
  '9F0E': { name: 'Issuer Action Code, Denial', desc: 'TVR bits for which the issuer wants an offline decline.', decode: v => ({ summary: hx(v), bits: bitsFor(v, TVR) }) },
  '9F0F': { name: 'Issuer Action Code, Online', desc: 'TVR bits for which the issuer wants the transaction sent online.', decode: v => ({ summary: hx(v), bits: bitsFor(v, TVR) }) },
  '9F10': { name: 'Issuer Application Data (IAD)', desc: 'Proprietary data from the card for the issuer: key index, cryptogram version and the Card Verification Results (CVR).', decode: v => iad(v) },
  '9F12': { name: 'Application Preferred Name', desc: 'Name of the application in the issuer\'s preferred language.', decode: v => ({ summary: ascii(v) }) },
  '9F15': { name: 'Merchant Category Code', desc: 'ISO 18245 MCC of the merchant.', decode: v => ({ summary: bcd(v) }) },
  '9F16': { name: 'Merchant Identifier', desc: 'Merchant id assigned by the acquirer.', decode: v => ({ summary: ascii(v).trim() }) },
  '9F1A': { name: 'Terminal Country Code', desc: 'ISO 3166 numeric country of the terminal.', decode: v => country(v) },
  '9F1C': { name: 'Terminal Identification', desc: 'Terminal id assigned by the acquirer.', decode: v => ({ summary: ascii(v).trim() }) },
  '9F1E': { name: 'Interface Device (IFD) Serial Number', desc: 'Serial number of the terminal hardware.', decode: v => ({ summary: ascii(v) }) },
  '9F21': { name: 'Transaction Time', desc: 'Local time at the terminal, HHMMSS.', decode: v => { const s = bcd(v); return { summary: `${s.slice(0, 2)}:${s.slice(2, 4)}:${s.slice(4, 6)}` } } },
  '9F26': { name: 'Application Cryptogram (AC)', desc: 'The cryptogram the card generated (type in 9F27). For an ARQC the issuer recomputes it with the card key to prove the card is genuine and the data unaltered.', decode: v => ({ summary: hx(v) }) },
  '9F27': { name: 'Cryptogram Information Data (CID)', desc: 'Type of cryptogram in 9F26 and why.', decode: v => cid(v) },
  '9F33': { name: 'Terminal Capabilities', desc: 'Card input, cardholder verification and security capabilities of the terminal.', decode: v => { const bits = bitsFor(v, TERM_CAPS); return { summary: listSet(bits, 4), bits } } },
  '9F34': { name: 'Cardholder Verification Method (CVM) Results', desc: 'The CVM performed, the condition that selected it and the result.', decode: v => cvm(v) },
  '9F35': { name: 'Terminal Type', desc: 'Who operates the terminal and how it connects: first digit operator, second environment.', decode: v => { const s = bcd(v); return { summary: `${s} · ${TERMINAL_OPERATOR[s[0]] || '?'}, ${(TERMINAL_ENV[s[1]] || '?').toLowerCase()}` } } },
  '9F36': { name: 'Application Transaction Counter (ATC)', desc: 'Counter kept by the card, incremented on every transaction. Used in the session key; a value not above the last one seen means a replay.', decode: v => ({ summary: `${parseInt(hx(v), 16)} (0x${hx(v)})` }) },
  '9F37': { name: 'Unpredictable Number (UN)', desc: 'Random number from the terminal, included in the cryptogram so it cannot be precomputed.', decode: v => ({ summary: hx(v) }) },
  '9F39': { name: 'POS Entry Mode', desc: 'How the card data was read (05 chip, 07 contactless chip, 90 magstripe, 80 fallback).', decode: v => ({ summary: bcd(v) }) },
  '9F40': { name: 'Additional Terminal Capabilities', desc: 'Transaction types, input and output capabilities of the terminal.' },
  '9F41': { name: 'Transaction Sequence Counter', desc: 'Counter kept by the terminal, incremented per transaction.', decode: v => ({ summary: bcd(v).replace(/^0+(?=\d)/, '') }) },
  '9F53': { name: 'Transaction Category Code', desc: 'Mastercard: kind of transaction (e.g. R retail, C cash).', decode: v => ({ summary: ascii(v) }) },
  '9F5B': { name: 'Issuer Script Results', desc: 'Result of each issuer script command run by the card.' },
  '9F66': { name: 'Terminal Transaction Qualifiers (TTQ)', desc: 'Contactless: the reader\'s capabilities and requirements for this transaction.' },
  '9F6C': { name: 'Card Transaction Qualifiers (CTQ)', desc: 'Contactless: card preferences for CVM and offline processing.' },
  '9F6E': { name: 'Form Factor Indicator / Third Party Data', desc: 'Device form factor (card, phone, wearable) or scheme specific data.' },
  '9F7C': { name: 'Customer Exclusive Data', desc: 'Issuer proprietary data from the card.' },
  71: { name: 'Issuer Script Template 1', desc: 'Commands for the card, run before the final GENERATE AC.' },
  72: { name: 'Issuer Script Template 2', desc: 'Commands for the card, run after the final GENERATE AC (e.g. PIN unblock, limits update).' },
  86: { name: 'Issuer Script Command', desc: 'One APDU command inside a script template.' }
}

function listSet (bits, max = 6) {
  const set = bits.flatMap(b => b.flags.filter(f => f.set && f.value === undefined).map(f => f.text))
  if (!set.length) return 'None'
  return set.length > max ? `${set.slice(0, max).join(' · ')} · +${set.length - max} more` : set.join(' · ')
}
function country (v) { const c = bcd(v).slice(-3); return { summary: `${c} · ${COUNTRIES[c] || 'see ISO 3166'}` } }
function currency (v) { const c = bcd(v).slice(-3); return { summary: `${c} · ${CURRENCIES[c] || 'see ISO 4217'}` } }
function aid (v) {
  const s = hx(v)
  const rid = s.slice(0, 10)
  const owner = RIDS[rid] || (s[0] === 'F' ? 'Proprietary (unregistered RID)' : 'Unknown RID')
  return { summary: `${owner} · PIX ${s.slice(10) || '-'}`, details: [{ label: 'RID', value: `${rid} · ${owner}` }, { label: 'PIX', value: s.slice(10) || '-' }] }
}
function cid (v) {
  const b = v[0]
  const types = { 0: 'AAC (declined by the card)', 1: 'TC (approved offline)', 2: 'ARQC (go online)', 3: 'RFU' }
  const reasons = { 0: 'No information given', 1: 'Service not allowed', 2: 'PIN try limit exceeded', 3: 'Issuer authentication failed' }
  const bits = [{ index: 1, hex: hx(v), bin: bin(b), flags: [
    { bit: 'b8-b7', text: 'Cryptogram type', set: true, value: types[b >> 6] },
    { bit: 'b4', text: 'Advice required', set: (b & 0x08) !== 0 },
    { bit: 'b3-b1', text: 'Reason / advice code', set: true, value: reasons[b & 0x07] || 'RFU' }] }]
  return { summary: types[b >> 6], bits }
}
function cvm (v) {
  if (v.length !== 3) return { summary: hx(v) }
  const code = v[0] & 0x3F
  const details = [
    { label: 'Method', value: `${hx([v[0]])} · ${CVM_CODES[code] || 'Issuer / scheme specific'}${v[0] & 0x40 ? ' (apply next rule if unsuccessful)' : ''}` },
    { label: 'Condition', value: `${hx([v[1]])} · ${CVM_CONDITIONS[v[1]] || 'RFU / scheme specific'}` },
    { label: 'Result', value: `${hx([v[2]])} · ${CVM_RESULTS[v[2]] || 'RFU'}` }]
  return { summary: `${CVM_CODES[code] || 'CVM ' + hx([v[0]])} · ${CVM_RESULTS[v[2]] || '?'}`, details, warn: v[2] === 0x01 }
}
function iad (v) {
  // Visa VIS format: length 06, derivation key index, cryptogram version, CVR (length + 3 bytes)
  if (v.length >= 7 && v[0] === 0x06 && v[3] === 0x03) {
    const bits = bitsFor(v.slice(4, 7), VISA_CVR).map((b, k) => ({ ...b, index: `CVR ${k + 2}` }))
    return {
      summary: `Visa format · ${CVN[v[2]] || 'CVN ' + hx([v[2]])} · CVR ${hx(v.slice(3, 7))}`,
      details: [
        { label: 'Length', value: hx([v[0]]) },
        { label: 'Derivation key index', value: hx([v[1]]) },
        { label: 'Cryptogram version', value: `${hx([v[2]])} · ${CVN[v[2]] || 'unknown'}` },
        { label: 'CVR', value: `${hx(v.slice(3, 7))} (length ${v[3]} + 3 bytes, broken down below)` },
        ...(v.length > 7 ? [{ label: 'Issuer discretionary data', value: hx(v.slice(7)) }] : [])],
      bits
    }
  }
  return { summary: `${v.length} bytes, scheme specific format`, details: [{ label: 'Raw', value: hx(v) }] }
}
function issuerAuth (v) {
  if (v.length !== 10) return { summary: hx(v) }
  const code = ascii(v.slice(8))
  return {
    summary: `ARPC ${hx(v.slice(0, 8))} · ARC ${code} ${ARC[code] ? '(' + ARC[code] + ')' : ''}`,
    details: [{ label: 'ARPC', value: `${hx(v.slice(0, 8))} (method 1: over the ARQC XOR the ARC)` },
      { label: 'ARC', value: `${hx(v.slice(8))} = "${code}" · ${ARC[code] || 'scheme specific'}` }],
    warn: code !== '00'
  }
}

// tags where a set bit reports a problem (red); elsewhere a set bit is a capability (green)
const ALERT_TAGS = new Set(['95', '9F10', '9F0D', '9F0E', '9F0F'])

/** Decodes field 55 hex into display rows (recursive for constructed tags). */
export function decodeIcc (hex) {
  const walk = nodes => nodes.map(n => {
    const def = TAGS[n.tag]
    let d = {}
    try { d = def?.decode ? def.decode(n.value) : {} } catch (e) { d = { summary: `Cannot interpret: ${e.message}`, warn: true } }
    const sensitive = n.tag === '5A' || n.tag === '57'
    return {
      tag: n.tag,
      length: n.length,
      name: def?.name || (n.tag.startsWith('DF') || n.tag.startsWith('9F7') ? 'Proprietary / scheme specific' : 'Unknown tag'),
      desc: def?.desc || '',
      raw: sensitive ? '(masked)' : hx(n.value),
      summary: d.summary ?? (n.children ? `${n.children.length} objects` : hx(n.value)),
      bits: d.bits || null,
      details: d.details || null,
      warn: !!d.warn,
      known: !!def,
      tone: ALERT_TAGS.has(n.tag) ? 'alert' : 'info',
      children: n.children ? walk(n.children) : null
    }
  })
  return walk(parseTlv(hex))
}

/** TVR scenarios for the simulator. */
export const TVR_PRESETS = [
  { label: 'Clean: all checks passed', value: '0000000000' },
  { label: 'Online PIN entered', value: '0000040000' },
  { label: 'Exceeds floor limit + online PIN', value: '0000048000' },
  { label: 'Offline data authentication not performed', value: '8000000000' },
  { label: 'New card', value: '0008000000' },
  { label: 'Expired application', value: '0040000000' },
  { label: 'PIN try limit exceeded', value: '0000200000' },
  { label: 'Merchant forced online', value: '0000000800' },
  { label: 'Selected randomly for online', value: '0000001000' }
]
