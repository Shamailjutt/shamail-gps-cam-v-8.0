import { useState } from 'react';
import {
  Download,
  Share2,
  Trash2,
  X,
  Camera,
  CheckCircle2,
  CalendarClock,
  ImagePlus,
  FileSearch,
  ShieldCheck,
} from 'lucide-react';
import { CapturedPhoto, OriginalPhotoRecord } from '../types';
import {
  computeSha256Hex,
  dataUrlToUint8Array,
  ParsedExifMetadata,
  parseJpegExif,
} from '../utils/jpegExif';
import { triggerOriginalDownload, triggerPhotoDownload } from '../utils/photoDb';

interface GalleryViewProps {
  photos: CapturedPhoto[];
  originals: OriginalPhotoRecord[];
  onDelete: (id: string) => void;
  onEditPhoto: (photo: CapturedPhoto) => void;
  onEditOriginal: (original: OriginalPhotoRecord) => void;
  onDeleteOriginal: (id: string) => void;
  onOpenGalleryGps: () => void;
  onReturnToCamera: () => void;
}

interface ExifInspectionState {
  title: string;
  roleLabel: string;
  byteLength: number;
  sha256: string;
  exif: ParsedExifMetadata;
}

function formatRationalValue(r?: { numerator: number; denominator: number }): string {
  if (!r || r.denominator === 0) return '—';
  if (r.numerator === 0) return '0';
  if (r.denominator === 1) return String(r.numerator);
  if (r.numerator < r.denominator && r.numerator === 1) {
    return `1/${r.denominator}s`;
  }
  return (r.numerator / r.denominator).toFixed(2);
}

export function GalleryView({
  photos,
  originals,
  onDelete,
  onEditPhoto,
  onEditOriginal,
  onDeleteOriginal,
  onOpenGalleryGps,
  onReturnToCamera,
}: GalleryViewProps) {
  const [section, setSection] = useState<'exports' | 'originals'>('exports');
  const [selectedPhoto, setSelectedPhoto] = useState<CapturedPhoto | null>(null);
  const [shareFeedback, setShareFeedback] = useState<string | null>(null);
  const [exifReport, setExifReport] = useState<ExifInspectionState | null>(null);

  const handleSharePhoto = async (photo: CapturedPhoto) => {
    try {
      const bytes =
        photo.jpegBytes && photo.jpegBytes.byteLength > 0
          ? new Uint8Array(photo.jpegBytes)
          : dataUrlToUint8Array(photo.dataUrl);
      const exactBuffer = new ArrayBuffer(bytes.byteLength);
      new Uint8Array(exactBuffer).set(bytes);
      const blob = new Blob([exactBuffer], { type: 'image/jpeg' });
      const file = new File([blob], photo.fileName, { type: 'image/jpeg' });

      if (navigator.canShare && navigator.canShare({ files: [file] })) {
        await navigator.share({
          title: photo.fileName,
          text: `${photo.organizationName} · ${photo.locationHeadline}`,
          files: [file],
        });
        return;
      }
    } catch {
      // Fall back to direct file download when Web Share API is unavailable
    }
    triggerPhotoDownload(photo);
    setShareFeedback(`Saved ${photo.fileName} to Downloads`);
    setTimeout(() => setShareFeedback(null), 3000);
  };

  const inspectExportedExif = async (photo: CapturedPhoto) => {
    const bytes =
      photo.jpegBytes && photo.jpegBytes.byteLength > 0
        ? new Uint8Array(photo.jpegBytes)
        : dataUrlToUint8Array(photo.dataUrl);
    const exif = parseJpegExif(bytes);
    const sha256 = await computeSha256Hex(bytes);
    setExifReport({
      title: photo.fileName,
      roleLabel: 'Exported Watermarked JPEG',
      byteLength: bytes.byteLength,
      sha256,
      exif,
    });
  };

  const inspectOriginalExif = async (orig: OriginalPhotoRecord) => {
    const bytes = new Uint8Array(orig.bytes);
    const exif = parseJpegExif(bytes);
    const sha256 = await computeSha256Hex(bytes);
    setExifReport({
      title: orig.fileName,
      roleLabel: 'Untouched Original Photo',
      byteLength: bytes.byteLength,
      sha256,
      exif,
    });
  };

  if (photos.length === 0 && originals.length === 0) {
    return (
      <div className="max-w-3xl mx-auto py-16 px-6 text-center bg-[#14171F] border border-[#2B3142] rounded-2xl">
        <div className="w-12 h-12 rounded-xl bg-amber-500/10 border border-amber-500/30 flex items-center justify-center mx-auto mb-4">
          <Camera className="w-6 h-6 text-amber-400" />
        </div>
        <h2 className="text-xl font-bold text-slate-100">No Stamped GPS Photos Yet</h2>
        <p className="text-sm text-slate-400 mt-2 max-w-md mx-auto">
          Capture a live camera photo or use <strong>Gallery GPS Camera</strong> to stamp any
          existing gallery photo with SHAMAIL PERA location, coordinates, and date/time while
          keeping your original photo completely untouched.
        </p>
        <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
          <button
            type="button"
            onClick={onReturnToCamera}
            className="inline-flex items-center gap-2 px-5 py-2.5 min-h-[44px] rounded-xl bg-amber-500 hover:bg-amber-400 text-neutral-950 text-sm font-bold transition-colors cursor-pointer"
          >
            <Camera className="w-4 h-4" />
            Open Camera Viewfinder
          </button>
          <button
            type="button"
            onClick={onOpenGalleryGps}
            className="inline-flex items-center gap-2 px-5 py-2.5 min-h-[44px] rounded-xl border border-amber-500/60 hover:bg-amber-500/15 text-amber-300 text-sm font-semibold transition-colors cursor-pointer"
          >
            <ImagePlus className="w-4 h-4" />
            Gallery GPS Camera
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4 border-b border-[#2B3142] pb-4">
        <div>
          <h1 className="text-xl font-bold text-slate-100">GPS Photo Gallery</h1>
          <div className="flex flex-wrap items-center gap-2 text-xs text-slate-400 mt-1">
            <span className="font-mono tabular-nums">{photos.length} export(s)</span>
            <span aria-hidden="true">·</span>
            <span className="font-mono tabular-nums">{originals.length} untouched original(s)</span>
            <span aria-hidden="true">·</span>
            <span className="font-mono text-amber-300">Pictures/GpsCamByShamail</span>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          {shareFeedback && <span className="text-xs text-emerald-400">{shareFeedback}</span>}
          <div className="flex items-center gap-1 p-1 bg-[#0D0F14] rounded-xl border border-[#2B3142]">
            <button
              type="button"
              onClick={() => setSection('exports')}
              className={`px-3 min-h-[34px] rounded-lg text-xs font-semibold transition-colors cursor-pointer ${
                section === 'exports'
                  ? 'bg-amber-500 text-neutral-950'
                  : 'text-slate-300 hover:text-white'
              }`}
            >
              Exported Photos ({photos.length})
            </button>
            <button
              type="button"
              onClick={() => setSection('originals')}
              className={`px-3 min-h-[34px] rounded-lg text-xs font-semibold transition-colors cursor-pointer ${
                section === 'originals'
                  ? 'bg-amber-500 text-neutral-950'
                  : 'text-slate-300 hover:text-white'
              }`}
            >
              Imported Originals ({originals.length})
            </button>
          </div>
          <button
            type="button"
            onClick={onOpenGalleryGps}
            className="inline-flex items-center gap-2 px-4 min-h-[40px] rounded-xl bg-amber-500 hover:bg-amber-400 text-neutral-950 text-xs font-bold transition-colors cursor-pointer"
          >
            <ImagePlus className="w-3.5 h-3.5" />
            Gallery GPS Camera
          </button>
        </div>
      </div>

      {section === 'exports' ? (
        photos.length === 0 ? (
          <div className="py-12 px-6 text-center bg-[#14171F] border border-[#2B3142] rounded-2xl text-sm text-slate-400">
            No exported GPS photos yet. Select an untouched original or capture a new photo.
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
            {photos.map((photo) => (
              <div
                key={photo.id}
                className="bg-[#14171F] border border-[#2B3142] rounded-2xl overflow-hidden flex flex-col"
              >
                <button
                  type="button"
                  onClick={() => setSelectedPhoto(photo)}
                  className="relative aspect-video bg-[#07080B] overflow-hidden group cursor-pointer focus:outline-none"
                >
                  <img
                    src={photo.dataUrl}
                    alt={photo.locationHeadline}
                    referrerPolicy="no-referrer"
                    className="w-full h-full object-cover group-hover:scale-[1.02] transition-transform duration-200"
                  />
                </button>

                <div className="p-4 flex-1 flex flex-col justify-between space-y-3">
                  <div className="space-y-1">
                    <p className="text-sm font-semibold text-slate-100 truncate">
                      {photo.locationHeadline}
                    </p>
                    <p className="text-xs font-mono tabular-nums text-amber-300 truncate">
                      {photo.displayedTimestampText}
                    </p>
                    <p className="text-[11px] font-mono text-slate-400 truncate">
                      {photo.fileName}
                    </p>
                    <div className="flex items-center gap-1.5 text-xs pt-0.5 text-slate-400">
                      <CheckCircle2 className="w-3.5 h-3.5 text-amber-400 shrink-0" />
                      <span className="truncate">
                        {photo.originalFileName
                          ? `Source Original: ${photo.originalFileName}`
                          : photo.isGalleryExport
                            ? 'Gallery GPS Export'
                            : 'SHAMAIL GPS Export'}
                      </span>
                    </div>
                  </div>

                  <div className="space-y-2 pt-2 border-t border-[#2B3142]">
                    <div className="grid grid-cols-2 gap-2">
                      <button
                        type="button"
                        onClick={() => onEditPhoto(photo)}
                        className="inline-flex items-center justify-center gap-1.5 min-h-[38px] px-2.5 rounded-lg border border-amber-500/50 hover:bg-amber-500/15 text-amber-300 text-xs font-semibold transition-colors whitespace-nowrap cursor-pointer"
                      >
                        <CalendarClock className="w-3.5 h-3.5" />
                        Customize Copy
                      </button>
                      <button
                        type="button"
                        onClick={() => inspectExportedExif(photo)}
                        className="inline-flex items-center justify-center gap-1.5 min-h-[38px] px-2.5 rounded-lg border border-[#2B3142] hover:bg-[#1B1F2B] text-slate-200 text-xs font-medium transition-colors whitespace-nowrap cursor-pointer"
                      >
                        <FileSearch className="w-3.5 h-3.5 text-amber-400" />
                        Inspect EXIF
                      </button>
                    </div>

                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        onClick={() => triggerPhotoDownload(photo)}
                        className="flex-1 inline-flex items-center justify-center gap-1.5 min-h-[40px] px-3 rounded-lg bg-amber-500 hover:bg-amber-400 text-neutral-950 text-xs font-bold transition-colors whitespace-nowrap cursor-pointer"
                      >
                        <Download className="w-3.5 h-3.5" />
                        Save JPEG
                      </button>
                      <button
                        type="button"
                        onClick={() => handleSharePhoto(photo)}
                        className="inline-flex items-center justify-center gap-1.5 min-h-[40px] px-3 rounded-lg border border-[#2B3142] hover:bg-[#1B1F2B] text-slate-200 text-xs font-medium transition-colors whitespace-nowrap cursor-pointer"
                      >
                        <Share2 className="w-3.5 h-3.5 text-amber-400" />
                        Share
                      </button>
                      <button
                        type="button"
                        onClick={() => onDelete(photo.id)}
                        aria-label={`Delete ${photo.fileName}`}
                        className="inline-flex items-center justify-center min-h-[40px] min-w-[40px] rounded-lg border border-[#2B3142] hover:border-red-500/50 hover:bg-red-500/10 text-red-400 transition-colors cursor-pointer"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )
      ) : originals.length === 0 ? (
        <div className="py-12 px-6 text-center bg-[#14171F] border border-[#2B3142] rounded-2xl space-y-3">
          <p className="text-sm text-slate-300">
            No imported gallery originals stored yet.
          </p>
          <p className="text-xs text-slate-400 max-w-md mx-auto">
            Whenever you pick a photo in <strong>Gallery GPS Camera</strong>, its exact original
            bytes and original EXIF metadata are preserved here untouched so you can create new
            exports anytime.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
          {originals.map((orig) => (
            <div
              key={orig.id}
              className="bg-[#14171F] border border-[#2B3142] rounded-2xl overflow-hidden flex flex-col"
            >
              <div className="relative aspect-video bg-[#07080B] overflow-hidden">
                <img
                  src={orig.dataUrl}
                  alt={orig.fileName}
                  referrerPolicy="no-referrer"
                  className="w-full h-full object-cover"
                />
              </div>

              <div className="p-4 flex-1 flex flex-col justify-between space-y-3">
                <div className="space-y-1">
                  <div className="flex items-center gap-1.5 text-xs text-emerald-400 font-semibold">
                    <ShieldCheck className="w-3.5 h-3.5 shrink-0" />
                    <span>Original Untouched Source</span>
                  </div>
                  <p className="text-sm font-semibold text-slate-100 truncate">{orig.fileName}</p>
                  <p className="text-xs font-mono text-slate-400 truncate">
                    EXIF Time:{' '}
                    {orig.originalExif?.dateTimeOriginal ||
                      orig.originalExif?.dateTime ||
                      'None in source'}
                  </p>
                  <p className="text-xs font-mono text-slate-400 truncate">
                    {typeof orig.originalExif?.latitude === 'number' &&
                    typeof orig.originalExif?.longitude === 'number'
                      ? `GPS: ${orig.originalExif.latitude.toFixed(6)}, ${orig.originalExif.longitude.toFixed(6)}`
                      : 'GPS: None in source EXIF'}
                  </p>
                  <p className="text-[10px] font-mono text-slate-500 truncate">
                    {(orig.byteLength / 1024).toFixed(1)} KB · SHA-256: {orig.sha256.slice(0, 14)}…
                  </p>
                </div>

                <div className="space-y-2 pt-2 border-t border-[#2B3142]">
                  <div className="grid grid-cols-2 gap-2">
                    <button
                      type="button"
                      onClick={() => onEditOriginal(orig)}
                      className="inline-flex items-center justify-center gap-1.5 min-h-[38px] px-2.5 rounded-lg bg-amber-500 hover:bg-amber-400 text-neutral-950 text-xs font-bold transition-colors whitespace-nowrap cursor-pointer"
                    >
                      <ImagePlus className="w-3.5 h-3.5" />
                      Create Export
                    </button>
                    <button
                      type="button"
                      onClick={() => inspectOriginalExif(orig)}
                      className="inline-flex items-center justify-center gap-1.5 min-h-[38px] px-2.5 rounded-lg border border-[#2B3142] hover:bg-[#1B1F2B] text-slate-200 text-xs font-medium transition-colors whitespace-nowrap cursor-pointer"
                    >
                      <FileSearch className="w-3.5 h-3.5 text-amber-400" />
                      Inspect EXIF
                    </button>
                  </div>

                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => triggerOriginalDownload(orig)}
                      className="flex-1 inline-flex items-center justify-center gap-1.5 min-h-[38px] px-3 rounded-lg border border-amber-500/50 hover:bg-amber-500/15 text-amber-300 text-xs font-semibold transition-colors whitespace-nowrap cursor-pointer"
                    >
                      <Download className="w-3.5 h-3.5" />
                      Download Original
                    </button>
                    <button
                      type="button"
                      onClick={() => onDeleteOriginal(orig.id)}
                      aria-label={`Delete original ${orig.fileName}`}
                      className="inline-flex items-center justify-center min-h-[38px] min-w-[38px] rounded-lg border border-[#2B3142] hover:border-red-500/50 hover:bg-red-500/10 text-red-400 transition-colors cursor-pointer"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Full-Resolution Lightbox Modal */}
      {selectedPhoto && (
        <div className="fixed inset-0 z-50 bg-black/90 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-[#0D0F14] border border-[#2B3142] rounded-2xl max-w-5xl w-full max-h-[92vh] flex flex-col overflow-hidden">
            <div className="flex items-center justify-between gap-4 px-5 py-3.5 border-b border-[#2B3142] bg-[#14171F]">
              <div className="min-w-0">
                <h3 className="text-sm font-semibold text-slate-100 truncate">
                  {selectedPhoto.fileName}
                </h3>
                <div className="flex items-center gap-2 text-xs text-slate-400 font-mono tabular-nums">
                  <span>
                    {selectedPhoto.width} × {selectedPhoto.height}px
                  </span>
                  <span aria-hidden="true">·</span>
                  <span>{selectedPhoto.displayedTimestampText}</span>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setSelectedPhoto(null)}
                aria-label="Close photo viewer"
                className="min-h-[40px] min-w-[40px] rounded-lg border border-[#2B3142] flex items-center justify-center text-slate-300 hover:text-white cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="flex-1 overflow-auto bg-[#07080B] p-4 flex items-center justify-center">
              <img
                src={selectedPhoto.dataUrl}
                alt={selectedPhoto.fileName}
                referrerPolicy="no-referrer"
                className="max-h-[68vh] w-auto object-contain rounded-lg"
              />
            </div>

            <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-3.5 border-t border-[#2B3142] bg-[#14171F]">
              <span className="text-xs font-mono tabular-nums text-slate-300">
                {selectedPhoto.coordinatesText}
              </span>
              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  onClick={() => inspectExportedExif(selectedPhoto)}
                  className="inline-flex items-center gap-1.5 px-3.5 min-h-[40px] rounded-lg border border-[#2B3142] hover:bg-[#1B1F2B] text-slate-200 text-xs font-medium transition-colors cursor-pointer"
                >
                  <FileSearch className="w-3.5 h-3.5 text-amber-400" />
                  Inspect EXIF
                </button>
                <button
                  type="button"
                  onClick={() => {
                    const target = selectedPhoto;
                    setSelectedPhoto(null);
                    onEditPhoto(target);
                  }}
                  className="inline-flex items-center gap-1.5 px-4 min-h-[40px] rounded-lg border border-amber-500/60 hover:bg-amber-500/15 text-amber-300 text-xs font-semibold transition-colors cursor-pointer"
                >
                  <CalendarClock className="w-3.5 h-3.5" />
                  Customize Copy
                </button>
                <button
                  type="button"
                  onClick={() => triggerPhotoDownload(selectedPhoto)}
                  className="inline-flex items-center gap-1.5 px-4 min-h-[40px] rounded-lg bg-amber-500 hover:bg-amber-400 text-neutral-950 text-xs font-bold transition-colors cursor-pointer"
                >
                  <Download className="w-3.5 h-3.5" />
                  Download JPEG
                </button>
                <button
                  type="button"
                  onClick={() => {
                    onDelete(selectedPhoto.id);
                    setSelectedPhoto(null);
                  }}
                  className="inline-flex items-center gap-1.5 px-4 min-h-[40px] rounded-lg border border-red-500/40 text-red-400 hover:bg-red-500/10 text-xs font-medium transition-colors cursor-pointer"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  Delete
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Real Binary EXIF Metadata Inspector Modal */}
      {exifReport && (
        <div className="fixed inset-0 z-50 bg-black/90 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-[#0D0F14] border border-[#2B3142] rounded-2xl max-w-xl w-full max-h-[88vh] flex flex-col overflow-hidden shadow-2xl">
            <div className="flex items-center justify-between gap-4 px-5 py-3.5 border-b border-[#2B3142] bg-[#14171F]">
              <div className="min-w-0">
                <span className="text-[10px] font-mono uppercase tracking-wider text-amber-400 block">
                  {exifReport.roleLabel}
                </span>
                <h3 className="text-sm font-bold text-slate-100 truncate">{exifReport.title}</h3>
              </div>
              <button
                type="button"
                onClick={() => setExifReport(null)}
                aria-label="Close EXIF inspector"
                className="min-h-[38px] min-w-[38px] rounded-lg border border-[#2B3142] flex items-center justify-center text-slate-300 hover:text-white cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-5 overflow-y-auto space-y-2.5 text-xs font-mono">
              {[
                ['EXIF Present', exifReport.exif.hasExif ? 'Yes (APP1 TIFF)' : 'No'],
                ['DateTime (0x0132)', exifReport.exif.dateTime || '—'],
                ['DateTimeOriginal (0x9003)', exifReport.exif.dateTimeOriginal || '—'],
                ['DateTimeDigitized (0x9004)', exifReport.exif.dateTimeDigitized || '—'],
                ['OffsetTimeOriginal', exifReport.exif.offsetTimeOriginal || '—'],
                ['SubSecTimeOriginal', exifReport.exif.subSecTimeOriginal || 'None'],
                [
                  'GPS Latitude',
                  typeof exifReport.exif.latitude === 'number'
                    ? exifReport.exif.latitude.toFixed(6)
                    : '—',
                ],
                [
                  'GPS Longitude',
                  typeof exifReport.exif.longitude === 'number'
                    ? exifReport.exif.longitude.toFixed(6)
                    : '—',
                ],
                ['GPSDateStamp', exifReport.exif.gpsDateStamp || '—'],
                [
                  'Camera Make / Model',
                  [exifReport.exif.make, exifReport.exif.model].filter(Boolean).join(' ') || '—',
                ],
                ['Lens Model', exifReport.exif.lensModel || '—'],
                [
                  'Exposure / F / ISO',
                  `${formatRationalValue(exifReport.exif.exposureTime)} · f/${formatRationalValue(
                    exifReport.exif.fNumber
                  )} · ISO ${exifReport.exif.isoSpeedRatings?.[0] ?? '—'}`,
                ],
                ['ImageDescription', exifReport.exif.imageDescription || '—'],
                ['UserComment', exifReport.exif.userComment || '—'],
                ['File Bytes', `${exifReport.byteLength.toLocaleString()} bytes`],
                ['SHA-256', exifReport.sha256],
              ].map(([label, val]) => (
                <div
                  key={label}
                  className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-1 px-3 py-2 rounded-lg bg-[#14171F] border border-[#2B3142]"
                >
                  <span className="text-slate-400">{label}</span>
                  <span className="text-slate-100 break-all sm:text-right">{val}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
