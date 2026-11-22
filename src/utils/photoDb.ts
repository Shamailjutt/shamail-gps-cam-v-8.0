import { CapturedPhoto, OriginalPhotoRecord } from '../types';
import { dataUrlToUint8Array } from './jpegExif';

const DB_NAME = 'GpsCamByShamailDB';
const DB_VERSION = 2;
const STORE_PHOTOS = 'photos';
const STORE_ORIGINALS = 'originals';

function openPhotoDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_PHOTOS)) {
        db.createObjectStore(STORE_PHOTOS, { keyPath: 'id' });
      }
      if (!db.objectStoreNames.contains(STORE_ORIGINALS)) {
        db.createObjectStore(STORE_ORIGINALS, { keyPath: 'id' });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function saveOriginalPhoto(original: OriginalPhotoRecord): Promise<void> {
  const db = await openPhotoDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_ORIGINALS, 'readwrite');
    const store = tx.objectStore(STORE_ORIGINALS);
    const getReq = store.get(original.id);
    getReq.onsuccess = () => {
      // Never overwrite an already-saved untouched original record
      if (!getReq.result) {
        store.put(original);
      }
    };
    tx.oncomplete = () => {
      db.close();
      resolve();
    };
    tx.onerror = () => {
      db.close();
      reject(tx.error);
    };
  });
}

export async function getOriginalPhoto(id: string): Promise<OriginalPhotoRecord | null> {
  const db = await openPhotoDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_ORIGINALS, 'readonly');
    const req = tx.objectStore(STORE_ORIGINALS).get(id);
    req.onsuccess = () => {
      db.close();
      resolve((req.result as OriginalPhotoRecord) || null);
    };
    req.onerror = () => {
      db.close();
      reject(req.error);
    };
  });
}

export async function listOriginalPhotos(): Promise<OriginalPhotoRecord[]> {
  const db = await openPhotoDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_ORIGINALS, 'readonly');
    const req = tx.objectStore(STORE_ORIGINALS).getAll();
    req.onsuccess = () => {
      db.close();
      const rows = (req.result as OriginalPhotoRecord[]) || [];
      rows.sort((a, b) => b.importedAtMillis - a.importedAtMillis);
      resolve(rows);
    };
    req.onerror = () => {
      db.close();
      reject(req.error);
    };
  });
}

export async function deleteOriginalPhoto(id: string): Promise<void> {
  const db = await openPhotoDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_ORIGINALS, 'readwrite');
    tx.objectStore(STORE_ORIGINALS).delete(id);
    tx.oncomplete = () => {
      db.close();
      resolve();
    };
    tx.onerror = () => {
      db.close();
      reject(tx.error);
    };
  });
}

export async function saveCapturedPhoto(photo: CapturedPhoto): Promise<void> {
  const db = await openPhotoDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_PHOTOS, 'readwrite');
    tx.objectStore(STORE_PHOTOS).put(photo);
    tx.oncomplete = () => {
      db.close();
      resolve();
    };
    tx.onerror = () => {
      db.close();
      reject(tx.error);
    };
  });
}

export async function listCapturedPhotos(): Promise<CapturedPhoto[]> {
  const db = await openPhotoDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_PHOTOS, 'readonly');
    const req = tx.objectStore(STORE_PHOTOS).getAll();
    req.onsuccess = () => {
      db.close();
      const rows = (req.result as CapturedPhoto[]) || [];
      rows.sort((a, b) => (b.exportedAtMillis ?? b.capturedDeviceMillis) - (a.exportedAtMillis ?? a.capturedDeviceMillis));
      resolve(rows);
    };
    req.onerror = () => {
      db.close();
      reject(req.error);
    };
  });
}

export async function deleteCapturedPhoto(id: string): Promise<void> {
  const db = await openPhotoDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_PHOTOS, 'readwrite');
    tx.objectStore(STORE_PHOTOS).delete(id);
    tx.oncomplete = () => {
      db.close();
      resolve();
    };
    tx.onerror = () => {
      db.close();
      reject(tx.error);
    };
  });
}

export async function ensureUniqueExportFileName(requestedName: string): Promise<string> {
  const existing = await listCapturedPhotos().catch(() => [] as CapturedPhoto[]);
  const names = new Set(existing.map((p) => p.fileName.toLowerCase()));
  if (!names.has(requestedName.toLowerCase())) return requestedName;
  const dot = requestedName.lastIndexOf('.');
  const stem = dot > 0 ? requestedName.slice(0, dot) : requestedName;
  const ext = dot > 0 ? requestedName.slice(dot) : '.jpg';
  let idx = 1;
  while (names.has(`${stem}_${idx}${ext}`.toLowerCase())) {
    idx++;
  }
  return `${stem}_${idx}${ext}`;
}

function downloadBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function triggerPhotoDownload(photo: CapturedPhoto): void {
  const bytes =
    photo.jpegBytes && photo.jpegBytes.byteLength > 0
      ? new Uint8Array(photo.jpegBytes)
      : dataUrlToUint8Array(photo.dataUrl);
  const exactBuffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(exactBuffer).set(bytes);
  const blob = new Blob([exactBuffer], { type: 'image/jpeg' });
  downloadBlob(blob, photo.fileName);
}

export function triggerOriginalDownload(original: OriginalPhotoRecord): void {
  const blob = new Blob([new Uint8Array(original.bytes)], {
    type: original.mimeType || 'image/jpeg',
  });
  downloadBlob(blob, original.fileName);
}
