import { deflateSync } from "node:zlib";

export function createTwoFrameApng(): Buffer {
  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(1, 0);
  ihdr.writeUInt32BE(1, 4);
  ihdr.set([8, 6, 0, 0, 0], 8);

  const animationControl = Buffer.alloc(8);
  animationControl.writeUInt32BE(2, 0);
  animationControl.writeUInt32BE(0, 4);

  const firstFrameControl = frameControl(0);
  const secondFrameControl = frameControl(1);
  const firstFrame = deflateSync(Buffer.from([0, 255, 0, 0, 255]));
  const secondFrameData = deflateSync(Buffer.from([0, 0, 0, 255, 255]));
  const secondFrame = Buffer.alloc(4 + secondFrameData.length);
  secondFrame.writeUInt32BE(2, 0);
  secondFrameData.copy(secondFrame, 4);

  return Buffer.concat([
    signature,
    pngChunk("IHDR", ihdr),
    pngChunk("acTL", animationControl),
    pngChunk("fcTL", firstFrameControl),
    pngChunk("IDAT", firstFrame),
    pngChunk("fcTL", secondFrameControl),
    pngChunk("fdAT", secondFrame),
    pngChunk("IEND", Buffer.alloc(0)),
  ]);
}

function frameControl(sequence: number): Buffer {
  const data = Buffer.alloc(26);
  data.writeUInt32BE(sequence, 0);
  data.writeUInt32BE(1, 4);
  data.writeUInt32BE(1, 8);
  data.writeUInt32BE(0, 12);
  data.writeUInt32BE(0, 16);
  data.writeUInt16BE(1, 20);
  data.writeUInt16BE(10, 22);
  data[24] = 0;
  data[25] = 0;
  return data;
}

function pngChunk(type: string, data: Buffer): Buffer {
  const typeBytes = Buffer.from(type, "ascii");
  const chunk = Buffer.alloc(12 + data.length);
  chunk.writeUInt32BE(data.length, 0);
  typeBytes.copy(chunk, 4);
  data.copy(chunk, 8);
  chunk.writeUInt32BE(crc32(Buffer.concat([typeBytes, data])), 8 + data.length);
  return chunk;
}

function crc32(bytes: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}
