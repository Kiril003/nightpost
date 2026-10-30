import { useState } from 'react';
import { DownloadCloud, UploadCloud, AlertTriangle, CheckCircle, Loader2 } from 'lucide-react';
import { readToken } from '../../services/tokenStore';
import { useTranslation } from '../../i18n/useTranslation';

export function BackupRestoreCard() {
  const { t } = useTranslation();
  const [downloading, setDownloading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  
  const [fileToRestore, setFileToRestore] = useState<File | null>(null);

  const handleDownload = async () => {
    setDownloading(true);
    setError(null);
    setSuccess(null);
    try {
      // In a real browser environment, we'd use a link or Blob creation.
      const token = readToken();
      const resp = await fetch('/api/v1/admin/backup/download', {
        method: 'GET',
        headers: {
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      });
      
      const blob = await resp.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      // Get filename from header or fallback
      const contentDisposition = resp.headers.get('content-disposition');
      let filename = 'phantom-backup.zip.enc';
      if (contentDisposition) {
        const match = contentDisposition.match(/filename="(.+)"/);
        if (match && match[1]) filename = match[1];
      }
      
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
      setSuccess(t('settings.backup.downloaded'));
    } catch (err: any) {
      setError(err.message || t('settings.backup.downloadFailed'));
    } finally {
      setDownloading(false);
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      setFileToRestore(e.target.files[0]);
    }
  };

  const confirmRestore = async () => {
    if (!fileToRestore) return;
    
    setUploading(true);
    setError(null);
    setSuccess(null);
    
    try {
      const formData = new FormData();
      formData.append('file', fileToRestore);

      // fetchApi with FormData usually needs careful handling or native fetch
      const token = readToken();
      const resp = await fetch('/api/v1/admin/backup/upload', {
        method: 'POST',
        headers: {
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: formData,
      });
      
      if (!resp.ok) {
        const errData = await resp.json();
        throw new Error(errData.detail || t('settings.backup.restoreFailedShort'));
      }
      
      setSuccess(t('settings.backup.restoreStarted'));
      setFileToRestore(null);
      
      // Force a reload after a delay to clear out frontend state
      setTimeout(() => {
        window.location.reload();
      }, 3000);
      
    } catch (err: any) {
      setError(err.message || t('settings.backup.restoreFailed'));
    } finally {
      setUploading(false);
    }
  };

  return (
    <div
      className="flex flex-col gap-4 p-4"
      style={{
        background: 'var(--ph-lit-white-a45)',
        borderRadius: 16,
        border: '1px solid var(--glass-border)',
      }}
    >
      <div className="flex items-center gap-2">
        <div
          style={{
            width: 32,
            height: 32,
            borderRadius: 8,
            background: 'color-mix(in srgb, var(--primary, var(--ph-lit-amber)) 15%, transparent)',
            color: 'var(--primary-shadow, var(--ph-lit-amber-ink))',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <DownloadCloud size={16} />
        </div>
        <div>
          <h3 style={{ fontSize: 'var(--fs-sm)', fontWeight: 600, color: 'var(--ink-strong)', margin: 0 }}>
            {t('settings.backup.title')}
          </h3>
          <p style={{ fontSize: 'var(--fs-xs)', color: 'var(--ink-secondary)', margin: 0, marginTop: 2 }}>
            {t('settings.backup.intro')}
          </p>
        </div>
      </div>

      <div className="flex flex-col gap-3 mt-2">
        <button
          onClick={handleDownload}
          disabled={downloading}
          className="phantom-button"
          style={{
            justifyContent: 'center',
            background: 'var(--primary, var(--ph-lit-amber))',
            color: 'var(--ph-lit-ink-espresso)',
            fontWeight: 600,
            border: 'none',
          }}
        >
          {downloading ? <Loader2 size={16} className="animate-spin" /> : <DownloadCloud size={16} />}
          {downloading ? t('settings.backup.creating') : t('settings.backup.save')}
        </button>

        <div style={{ height: 1, background: 'var(--ph-lit-black-a6)', margin: '8px 0' }} />

        <div className="flex items-center gap-2">
          <div
            style={{
              width: 32,
              height: 32,
              borderRadius: 8,
              background: 'color-mix(in srgb, var(--signal-alert, var(--ph-lit-red)) 10%, transparent)',
              color: 'var(--signal-alert, var(--ph-lit-red))',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <UploadCloud size={16} />
          </div>
          <div>
            <h3 style={{ fontSize: 'var(--fs-sm)', fontWeight: 600, color: 'var(--ink-strong)', margin: 0 }}>
              {t('settings.backup.restoreTitle')}
            </h3>
            <p style={{ fontSize: 'var(--fs-xs)', color: 'var(--ink-secondary)', margin: 0, marginTop: 2 }}>
              {t('settings.backup.restoreNote')}
            </p>
          </div>
        </div>

        <div className="flex gap-2">
          <input
            type="file"
            accept=".enc"
            id="backup-upload"
            style={{ display: 'none' }}
            onChange={handleFileChange}
          />
          <label
            htmlFor="backup-upload"
            className="phantom-button flex-1"
            style={{
              justifyContent: 'center',
              background: 'var(--ph-lit-white-a60)',
              border: '1px dashed var(--ph-lit-black-a10)',
              cursor: 'pointer',
              color: 'var(--ink-primary)',
            }}
          >
            {fileToRestore ? fileToRestore.name : t('settings.backup.pick')}
          </label>
        </div>

        {fileToRestore && (
          <div
            style={{
              background: 'color-mix(in srgb, var(--signal-alert, var(--ph-lit-red)) 8%, transparent)',
              border: '1px solid color-mix(in srgb, var(--signal-alert, var(--ph-lit-red)) 20%, transparent)',
              padding: 12,
              borderRadius: 12,
            }}
          >
            <div className="flex items-start gap-2 mb-3">
              <AlertTriangle size={16} color="var(--signal-alert, var(--ph-lit-red))" style={{ marginTop: 2 }} />
              <p style={{ fontSize: 'var(--fs-xs)', color: 'var(--ink-strong)', margin: 0, lineHeight: 1.4 }}>
                <strong>{t('settings.backup.warnLabel')}</strong> {t('settings.backup.warnBody')}
              </p>
            </div>
            <button
              onClick={confirmRestore}
              disabled={uploading}
              className="phantom-button"
              style={{
                width: '100%',
                justifyContent: 'center',
                background: 'var(--signal-alert, var(--ph-lit-red))',
                color: 'var(--ph-lit-white)',
                fontWeight: 600,
                border: 'none',
              }}
            >
              {uploading ? <Loader2 size={16} className="animate-spin" /> : t('settings.backup.confirm')}
            </button>
          </div>
        )}

        {error && (
          <div className="flex items-center gap-2 mt-2" style={{ color: 'var(--signal-alert, var(--ph-lit-red))', fontSize: 'var(--fs-xs)' }}>
            <AlertTriangle size={14} />
            {error}
          </div>
        )}
        {success && (
          <div className="flex items-center gap-2 mt-2" style={{ color: 'var(--signal-ok, var(--ph-lit-green))', fontSize: 'var(--fs-xs)' }}>
            <CheckCircle size={14} />
            {success}
          </div>
        )}
      </div>
    </div>
  );
}