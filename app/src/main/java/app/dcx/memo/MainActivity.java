package app.dcx.memo;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final int REQ_CREATE = 1002;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setDatabaseEnabled(true);
        web.addJavascriptInterface(new Bridge(), "MemoBridge");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "选择 JSON 文件"), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    toast("没有可用的文件管理器");
                    return false;
                }
            }
        });
        setContentView(web);

        // Android 8/9 以下导出需要写外部存储权限
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                   != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1002);
        }

        web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) result = new Uri[]{uri};
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        if (requestCode == REQ_CREATE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && pendingContent != null) {
                try {
                    OutputStream os = getContentResolver().openOutputStream(data.getData());
                    if (os != null) {
                        os.write(pendingContent.getBytes(StandardCharsets.UTF_8));
                        os.close();
                        toast("已保存");
                    } else {
                        toast("保存失败");
                    }
                } catch (Exception e) {
                    toast("保存失败：" + e.getMessage());
                }
            }
            pendingContent = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show(); }
        });
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".txt") || n.endsWith(".md")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".doc")) return "application/msword";
        if (n.endsWith(".docx"))
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        if (n.endsWith(".xlsx"))
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        return "*/*";
    }

    /** 供网页调用的桥。 */
    class Bridge {
        /** 尝试直接写入「下载」目录；返回落盘全路径，失败返回空串。 */
        @JavascriptInterface
        public String saveFile(String name, String content) {
            try {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/");
                    cv.put(MediaStore.Downloads.IS_PENDING, 1);
                    Uri uri = getContentResolver()
                            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return "";
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os == null) return "";
                    os.write(bytes);
                    os.close();
                    cv.clear();
                    cv.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, cv, null, null);
                    return "/storage/emulated/0/Download/" + name;
                }
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists() && !dir.mkdirs()) return "";
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(bytes);
                fos.close();
                return f.getAbsolutePath();
            } catch (Exception e) {
                return "";
            }
        }

        /** 兜底：让用户自己选保存位置（系统保存框），不依赖任何目录约定。 */
        @JavascriptInterface
        public boolean pickSave(String name, String content) {
            pendingContent = content;
            try {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(i, REQ_CREATE);
                return true;
            } catch (Exception e) {
                pendingContent = null;
                return false;
            }
        }

        /** 存附件：base64 内容写入应用私有目录，返回存储名（失败返回空串）。 */
        @JavascriptInterface
        public String saveAttachment(String fileName, String base64) {
            try {
                File dir = new File(getFilesDir(), "attachments");
                if (!dir.exists() && !dir.mkdirs()) return "";
                String safe = fileName == null ? "file"
                        : fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
                String stored = System.currentTimeMillis() + "_" + safe;
                File f = new File(dir, stored);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(android.util.Base64.decode(base64, android.util.Base64.DEFAULT));
                fos.close();
                return stored;
            } catch (Exception e) {
                return "";
            }
        }

        /** 打开附件：交给系统选择能处理它的应用。 */
        @JavascriptInterface
        public boolean openAttachment(String storedName) {
            try {
                File f = new File(new File(getFilesDir(), "attachments"), storedName);
                if (!f.exists()) { toast("附件已不存在"); return false; }
                Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        MainActivity.this, "app.dcx.memo.fileprovider", f);
                String mime = guessMime(f.getName());
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, mime);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(Intent.createChooser(i, "打开附件"));
                } catch (Exception noApp) {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(mime);
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "分享附件"));
                }
                return true;
            } catch (Exception e) {
                toast("打不开：" + e.getMessage());
                return false;
            }
        }

        /** 删除附件文件。 */
        @JavascriptInterface
        public boolean deleteAttachment(String storedName) {
            try {
                File f = new File(new File(getFilesDir(), "attachments"), storedName);
                return f.exists() && f.delete();
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String appVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }
    }
}
