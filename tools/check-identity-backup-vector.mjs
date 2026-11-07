// Synthetic independent OpenSSL/Node vector. Output contains no real identity or user passphrase.
import { pbkdf2Sync, createCipheriv } from 'node:crypto';
const password = 'Fixture 密码 e\u0301 🌏 2026';
const plaintext = Buffer.from('synthetic identity payload\n', 'utf8');
const salt = Buffer.from(Array.from({ length: 16 }, (_, i) => i));
const nonce = Buffer.from(Array.from({ length: 12 }, (_, i) => i + 16));
function encrypt(iterations) {
  const header = Buffer.alloc(56);
  Buffer.from('TS3M-ID-BACKUP\0\0', 'ascii').copy(header);
  header.writeUInt16BE(1, 16);
  header.writeUInt8(1, 18);
  header.writeUInt8(1, 19);
  header.writeUInt32BE(iterations, 20);
  header.writeUInt32BE(plaintext.length, 24);
  salt.copy(header, 28);
  nonce.copy(header, 44);
  const key = pbkdf2Sync(Buffer.from(password, 'utf8'), salt, iterations, 32, 'sha256');
  try {
    const cipher = createCipheriv('aes-256-gcm', key, nonce);
    cipher.setAAD(header);
    return Buffer.concat([header, cipher.update(plaintext), cipher.final(), cipher.getAuthTag()]);
  } finally {
    key.fill(0);
  }
}
console.log('# Independent Node/OpenSSL vector; UTF-8 Properties.load(Reader), no normalization.');
console.log('password=' + password);
console.log('plaintextHex=' + plaintext.toString('hex'));
console.log('containerHex=' + encrypt(600000).toString('hex'));
console.log('maxIterationsContainerHex=' + encrypt(2000000).toString('hex'));
