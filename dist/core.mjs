export const uid = () => {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  const bytes = new Uint8Array(16);
  if (globalThis.crypto?.getRandomValues) globalThis.crypto.getRandomValues(bytes);
  else for (let i=0;i<16;i++) bytes[i]=Math.floor(Math.random()*256);
  bytes[6]=(bytes[6]&15)|64; bytes[8]=(bytes[8]&63)|128;
  const h=Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
  return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;
};
export const MAX_EVENTS = 10000;
export const uuid = value => {
  let s = String(value ?? '').trim().toLowerCase().replace(/^0x/, '').replace(/[{}]/g, '');
  if (/^[a-f0-9]{4}$/.test(s)) s = `0000${s}-0000-1000-8000-00805f9b34fb`;
  if (/^[a-f0-9]{8}$/.test(s)) s = `${s}-0000-1000-8000-00805f9b34fb`;
  return s;
};
export const validUUID = value => /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(uuid(value));
export function hex(value) {
  if (Array.isArray(value)) {
    if (value.some(x => !Number.isInteger(x) || x < 0 || x > 255)) throw Error('Byte arrays must contain integers from 0 to 255.');
    value = value.map(x => x.toString(16).padStart(2, '0')).join('');
  }
  const s = String(value ?? '').trim().replace(/0x/gi, '').replace(/[\s:\-]/g, '');
  if (!s || !/^[0-9a-f]+$/i.test(s) || s.length % 2) throw Error('Payload must contain complete hexadecimal bytes, for example 01 0A FF.');
  if (s.length > 1024) throw Error('Payload exceeds the 512-byte GATT value limit.');
  return s.toUpperCase().match(/../g).join(' ');
}
export function normalize(row, index = 0) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) throw Error(`Row ${index + 1}: expected an observation object.`);
  const raw = String(row.direction ?? row.operation ?? row.type ?? row['btatt.opcode'] ?? '').toLowerCase();
  const names = { '0x12':'write', '0x52':'write', '0x1b':'notify', '0x1d':'indicate', '18':'write', '82':'write', '27':'notify', '29':'indicate' };
  const direction = names[raw] || (/write/.test(raw) ? 'write' : /notif/.test(raw) ? 'notify' : /indicat/.test(raw) ? 'indicate' : /^read$/.test(raw) ? 'read' : '');
  if (!direction) throw Error(`Row ${index + 1}: unsupported operation “${raw}”. Use write, notify, indicate or read.`);
  let response = row.response ?? row.write_response ?? null;
  if (['true','with_response','request'].includes(response)) response = true;
  if (['false','without_response','command'].includes(response)) response = false;
  if (response !== null && typeof response !== 'boolean') throw Error(`Row ${index + 1}: response must be true, false or null.`);
  if (['0x12','18','write_req','write_request'].includes(raw)) response = true;
  if (['0x52','82','write_cmd','write_command'].includes(raw)) response = false;
  let handle = String(row.handle ?? row['btatt.handle'] ?? '').trim().toLowerCase();
  if (handle && !/^0x[0-9a-f]{1,4}$/.test(handle)) throw Error(`Row ${index + 1}: handle must look like 0x0025.`);
  return {
    id: uid(), timestamp: String(row.timestamp ?? row.time ?? row['frame.time_relative'] ?? ''),
    action: String(row.action ?? row.button ?? row.label ?? 'Unlabeled').slice(0, 120),
    direction, service: uuid(row.service ?? row.service_uuid),
    characteristic: uuid(row.characteristic ?? row.char ?? row.characteristic_uuid ?? row.uuid),
    handle, connection: String(row.connection ?? row['bthci_acl.chandle'] ?? ''),
    value: hex(row.value ?? row.payload ?? row.data ?? row.bytes ?? row['btatt.value']),
    response: direction === 'write' ? response : null, source: String(row.source ?? 'capture'),
    synthetic: row.synthetic === true
  };
}
export function csvRows(text) {
  const delimiter = text.split(/\r?\n/)[0].includes('\t') ? '\t' : ',';
  const rows = []; let row = [], value = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (ch === '"') { if (quoted && text[i+1] === '"') { value += '"'; i++; } else quoted = !quoted; }
    else if (ch === delimiter && !quoted) { row.push(value); value = ''; }
    else if (ch === '\n' && !quoted) { row.push(value.replace(/\r$/, '')); rows.push(row); row = []; value = ''; }
    else value += ch;
  }
  if (quoted) throw Error('CSV has an unclosed quoted field.');
  if (value || row.length) { row.push(value.replace(/\r$/, '')); rows.push(row); }
  const headers = rows.shift()?.map(x => x.trim().toLowerCase()) || [];
  return rows.filter(r => r.some(x => x.trim())).map((r, i) => {
    if (r.length !== headers.length) throw Error(`CSV row ${i+2}: expected ${headers.length} columns, found ${r.length}.`);
    return Object.fromEntries(headers.map((key, j) => [key, r[j].trim()]));
  });
}
export function parseText(text) {
  const s = text.trim();
  if (!s) throw Error('Paste or select a capture first.');
  let rows;
  if (/^[{[]/.test(s) && !/^\[\d/.test(s)) {
    let parsed;
    try { parsed = JSON.parse(s); } catch { throw Error('Invalid JSON. Check commas, quotes and brackets; no data was imported.'); }
    rows = Array.isArray(parsed) ? parsed : parsed.observations || parsed.events || [parsed];
  } else if (/\b(direction|btatt.opcode|operation|type)\b/.test(s.split(/\r?\n/)[0]) && /[,\t]/.test(s.split(/\r?\n/)[0])) rows = csvRows(s);
  else rows = s.split(/\r?\n/).filter(l => l.trim() && !l.trim().startsWith('#')).map((line, i) => {
    const row = {};
    const re = /\b(action|button|label|timestamp|time|service_uuid|service|characteristic_uuid|characteristic|char|uuid|handle|connection|response|value|payload|data|bytes)\s*[=:]\s*(?:"([^"]*)"|'([^']*)'|(.+?)(?=\s+\w+\s*[=:]|$))/gi;
    for (const m of line.matchAll(re)) row[m[1].toLowerCase()] = m[2] ?? m[3] ?? m[4].trim();
    row.direction = line.match(/\b(write_req|write_cmd|write_request|write_command|write|notify|notification|indicate|indication|read)\b/i)?.[1] || '';
    row.timestamp ||= line.match(/^\[([^\]]+)\]/)?.[1] || '';
    if (!row.direction) throw Error(`Line ${i+1}: no supported GATT operation found.`);
    return row;
  });
  if (!Array.isArray(rows) || !rows.length) throw Error('No observation rows found.');
  if (rows.length > MAX_EVENTS) throw Error(`Split captures into at most ${MAX_EVENTS} observations per session.`);
  return { observations: rows.map(normalize), warnings: [] };
}
// Android btsnoop, datalink 1002 (H4). Only fixed ATT CID 0x0004 is decoded.
// Handles are deliberately unresolved: a handle is not a characteristic UUID.
export function parseBtsnoop(buffer) {
  const bytes = new Uint8Array(buffer), view = new DataView(buffer);
  if (bytes.length < 16 || new TextDecoder().decode(bytes.slice(0, 8)) !== 'btsnoop\0') throw Error('Not a btsnoop capture. PCAP/PCAPNG must first be exported as GATT CSV.');
  if (view.getUint32(8) !== 1 || view.getUint32(12) !== 1002) throw Error('Only btsnoop version 1, HCI UART (H4 / 1002) is supported. Export other formats as CSV.');
  const observations = [], warnings = [], fragments = new Map(), epochs = new Map(); let offset = 16, first = null, skipped = 0;
  while (offset < bytes.length) {
    if (offset + 24 > bytes.length) throw Error('Truncated btsnoop record header. Nothing imported.');
    const original = view.getUint32(offset), included = view.getUint32(offset+4), flags = view.getUint32(offset+8);
    const stamp = view.getBigUint64(offset+16); first ??= stamp;
    if (included > original || offset + 24 + included > bytes.length) throw Error('Truncated btsnoop packet. Nothing imported.');
    const packet = bytes.slice(offset+24, offset+24+included); offset += 24 + included;
    if (packet[0] === 4 && packet[1] === 5 && packet.length >= 7) {
      const handle = (packet[4] | (packet[5] << 8)) & 0xfff;
      epochs.set(handle,(epochs.get(handle)||0)+1);
      continue;
    }
    if (packet[0] !== 2 || packet.length < 5) continue;
    const p = new DataView(packet.buffer), bits = p.getUint16(1,true), connection = `0x${(bits & 0xfff).toString(16).padStart(3,'0')}#${epochs.get(bits & 0xfff)||0}`, boundary = (bits >> 12) & 3;
    const key = `${connection}:${flags & 1}`;
    if (p.getUint16(3,true) !== packet.length-5 || included !== original) { skipped++; fragments.delete(key); continue; }
    let data = packet.slice(5);
    if (boundary === 1) {
      const prev = fragments.get(key); if (!prev) { skipped++; continue; }
      data = new Uint8Array([...prev, ...data]);
    } else fragments.delete(key);
    if (data.length < 4) { skipped++; continue; }
    const d = new DataView(data.buffer), length = d.getUint16(0,true) + 4;
    if (data.length < length) { fragments.set(key,data); continue; }
    fragments.delete(key);
    if (data.length !== length || d.getUint16(2,true) !== 4) { skipped++; continue; }
    const opcode = data[4]; if (![0x12,0x52,0x1b,0x1d].includes(opcode)) continue;
    if (data.length < 8) { skipped++; continue; }
    observations.push(normalize({timestamp: (Number(stamp-first)/1e6).toFixed(6), direction: `0x${opcode.toString(16)}`, handle: `0x${d.getUint16(5,true).toString(16).padStart(4,'0')}`, connection, value: [...data.slice(7)], source:'Android btsnoop'}));
    if (observations.length > MAX_EVENTS) throw Error('Capture exceeds 10,000 ATT events. Split the capture before importing.');
  }
  if (!observations.length) throw Error('No supported ATT writes, notifications or indications found. Capture a fresh app session or export GATT CSV.');
  warnings.push('Handle-only capture: select the target connection, label button actions, and resolve handles to UUIDs from a GATT discovery before export.');
  warnings.push('Decodes fixed ATT only; EATT, signed/prepared writes, reads, discovery and application-level encryption are not decoded.');
  if (skipped || fragments.size) warnings.push(`${skipped + fragments.size} incomplete or unsupported ACL/L2CAP packets skipped.`);
  return {observations, warnings};
}
export function byteDiff(a, b) {
  const left = a ? hex(a).split(' ') : [], right = b ? hex(b).split(' ') : [];
  return Array.from({length:Math.max(left.length,right.length)},(_,i) => ({offset:i,a:left[i]??'—',b:right[i]??'—',changed:left[i]!==right[i],decimal:right[i] ? parseInt(right[i],16):null,ascii:right[i] && parseInt(right[i],16)>=32 && parseInt(right[i],16)<=126 ? String.fromCharCode(parseInt(right[i],16)):'.'}));
}
export const keyFor = event => [event.connection,event.service,event.characteristic,event.handle,event.value,event.response].join('|');
export function validateCommand(c, session) {
  const errors=[];
  if (session.synthetic || c.synthetic) errors.push('Synthetic sample');
  if (!validUUID(c.service) || !validUUID(c.characteristic)) errors.push('Resolve service and characteristic UUIDs');
  if (c.response !== true && c.response !== false) errors.push('Confirm write-with/without-response');
  if (c.stage !== 'tested' || !c.notes?.trim()) errors.push('Record a physical-device test and evidence');
  if (!c.name?.trim()) errors.push('Name the command');
  return errors;
}
export function mapping(session) {
  return {schema_version:2, device:session.device, synthetic:session.synthetic, session_id:session.id,
    limitations:['Temporal proximity is not proof of causality.','No encrypted application protocol decoding.','Only reviewed fixed-payload button commands are executable in the generated integration.'],
    gatt:session.catalog, observations:session.observations,
    commands:session.commands.map(c=>({...c,export_ready:validateCommand(c,session).length===0, blockers:validateCommand(c,session)}))};
}
export function sampleSession() {
  const rows=[];
  for (const [action,value,reply,time] of [['Power on','01 01','A1 01','0.000'],['Power on','01 01','A1 01','2.000'],['Power off','01 00','A1 00','4.000'],['Speed low','03 02','B0 02','6.000'],['Speed high','03 05','B0 05','8.000']]) {
    rows.push({action,value,timestamp:time,direction:'write_cmd',service:'fff0',characteristic:'fff1',synthetic:true});
    rows.push({action,value:reply,timestamp:(Number(time)+.065).toFixed(3),direction:'notify',service:'fff0',characteristic:'fff2',synthetic:true});
  }
  const observations=rows.map(normalize);
  return {id:uid(),name:'Demo • BLE fan',synthetic:true,device:{name:'Demo BLE fan',manufacturer:'Synthetic example',model:'Not a real device',address:''},observations,catalog:[],commands:[],warnings:[],created_at:new Date().toISOString()};
}
export function runtimeProfile(session) {
  if (!session.device.address.trim()) throw Error('Save the target Bluetooth address before exporting an install profile.');
  const commands = session.commands.filter(c => validateCommand(c,session).length === 0).map(c => ({id:c.id,name:c.name,service:c.service,characteristic:c.characteristic,value:c.value.replaceAll(' ',''),response:c.response,stage:c.stage,notes:c.notes,synthetic:false}));
  if (!commands.length) throw Error('No eligible commands. Resolve UUIDs, set write mode and record physical-device test evidence first.');
  if (commands.length > 128) throw Error('The experimental runtime supports up to 128 commands per device.');
  const profile={schema_version:2,device:{name:session.device.name,address:session.device.address},synthetic:false,commands};
  if (new TextEncoder().encode(JSON.stringify(profile)).length>65536) throw Error('Install profile exceeds 64 KiB. Shorten evidence notes or split the profile.');
  return profile;
}
