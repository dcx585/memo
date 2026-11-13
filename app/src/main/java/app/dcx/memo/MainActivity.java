package app.dcx.memo;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final int REQ_CREATE = 1002;
    private static final int REQ_TREE = 1003;
    private static final int REQ_IMPORT = 1004;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private byte[] pendingBytes;
    private String pendingMime = "application/octet-stream";
    private String pendingName = "file";

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
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "选择文件（可多选）"), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    toast("没有可用的文件管理器");
                    return false;
                }
            }
        });
        setContentView(web);

        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                   != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2001);
        }

        web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int k = 0; k < n; k++)
                        result[k] = data.getClipData().getItemAt(k).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }

        if (requestCode == REQ_CREATE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && pendingBytes != null) {
                try {
                    OutputStream os = getContentResolver().openOutputStream(data.getData());
                    if (os != null) {
                        os.write(pendingBytes);
                        os.close();
                        toast("已保存：" + pendingName);
                    } else toast("保存失败");
                } catch (Exception e) {
                    toast("保存失败：" + e.getMessage());
                }
            }
            pendingBytes = null;
            return;
        }

        if (requestCode == REQ_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                final Uri tree = data.getData();
                new Thread(new Runnable() {
                    public void run() { importTree(tree); }
                }).start();
            }
            return;
        }

        if (requestCode == REQ_IMPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                final Uri uri = data.getData();
                new Thread(new Runnable() {
                    public void run() { readBackup(uri); }
                }).start();
            } else {
                runOnUiThread(new Runnable() {
                    public void run() { callJs("window.__importResult&&window.__importResult('{\"kind\":\"cancel\"}')"); }
                });
            }
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

    private void callJs(final String js) {
        runOnUiThread(new Runnable() {
            public void run() { web.evaluateJavascript(js, null); }
        });
    }

    private File attestDir() {
        File dir = new File(getFilesDir(), "attachments");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 读回备份：zip → 解包附件 + 返回 notes.json；json/csv → 直接返回文本。 */
    private void readBackup(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) { toast("读不到这个文件"); return; }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) buf.write(b, 0, r);
            in.close();
            byte[] all = buf.toByteArray();

            if (all.length > 2 && all[0] == 'P' && all[1] == 'K') {
                ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(all));
                ZipEntry e;
                String notesJson = null;
                int restored = 0;
                File dir = attestDir();
                while ((e = zis.getNextEntry()) != null) {
                    String entryName = e.getName();
                    if (entryName.endsWith("notes.json")) {
                        ByteArrayOutputStream nb = new ByteArrayOutputStream();
                        byte[] tb = new byte[8192];
                        int tr;
                        while ((tr = zis.read(tb)) > 0) nb.write(tb, 0, tr);
                        notesJson = nb.toString("UTF-8");
                    } else if (entryName.startsWith("attachments/") && !e.isDirectory()) {
                        String base = entryName.substring("attachments/".length());
                        if (base.contains("/")) base = base.substring(base.lastIndexOf('/') + 1);
                        File out = new File(dir, base);
                        FileOutputStream fos = new FileOutputStream(out);
                        byte[] tb = new byte[8192];
                        int tr;
                        while ((tr = zis.read(tb)) > 0) fos.write(tb, 0, tr);
                        fos.close();
                        restored++;
                    }
                    zis.closeEntry();
                }
                zis.close();
                if (notesJson == null) { toast("备份里没有 notes.json"); return; }
                JSONObject res = new JSONObject();
                res.put("kind", "zip");
                res.put("text", notesJson);
                res.put("restored", restored);
                callJs("window.__importResult&&window.__importResult(" + JSONObject.quote(res.toString()) + ")");
                return;
            }

            String text = new String(all, StandardCharsets.UTF_8).trim();
            String kind = (text.startsWith("[") || text.startsWith("{")) ? "json" : "csv";
            JSONObject res = new JSONObject();
            res.put("kind", kind);
            res.put("text", text);
            callJs("window.__importResult&&window.__importResult(" + JSONObject.quote(res.toString()) + ")");
        } catch (Exception e) {
            toast("读取失败：" + e.getMessage());
        }
    }

    private void importTree(Uri treeUri) {
        try {
            androidx.documentfile.provider.DocumentFile root =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri);
            if (root == null) { toast("读取文件夹失败"); return; }
            File dir = attestDir();
            JSONArray out = new JSONArray();
            int[] budget = {40};
            long[] bytesLeft = {30L * 1024 * 1024};
            walk(root, dir, out, budget, bytesLeft, 0);
            final String json = out.toString();
            callJs("window.__folderResult&&window.__folderResult(" + json + ")");
        } catch (Exception e) {
            toast("导入失败：" + e.getMessage());
        }
    }

    private void walk(androidx.documentfile.provider.DocumentFile node, File dstDir,
                      JSONArray out, int[] budget, long[] bytesLeft, int depth) {
        if (depth > 4 || budget[0] <= 0 || node == null) return;
        for (androidx.documentfile.provider.DocumentFile f : node.listFiles()) {
            if (budget[0] <= 0) return;
            if (f.isDirectory()) { walk(f, dstDir, out, budget, bytesLeft, depth + 1); continue; }
            String name = f.getName() == null ? "file" : f.getName();
            String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            String stored = System.currentTimeMillis() + "_" + safe;
            try {
                InputStream in = getContentResolver().openInputStream(f.getUri());
                if (in == null) continue;
                File target = new File(dstDir, stored);
                FileOutputStream os = new FileOutputStream(target);
                byte[] buf = new byte[8192];
                long written = 0;
                int r;
                while ((r = in.read(buf)) > 0) {
                    if (written + r > bytesLeft[0]) { os.close(); in.close();
                        target.delete(); return; }
                    os.write(buf, 0, r); written += r;
                }
                os.close(); in.close();
                bytesLeft[0] -= written;
                budget[0]--;
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("stored", stored);
                o.put("size", written);
                out.put(o);
            } catch (Exception ignored) { }
        }
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
        if (n.endsWith(".csv")) return "text/csv";
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

    /** 网页侧调用的接口 */
    class Bridge {

        /** 打包备份：notes.json + attachments/* → zip，交给系统保存框。 */
        @JavascriptInterface
        public boolean exportZip(String notesJson, String fileName) {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ZipOutputStream zos = new ZipOutputStream(bos);
                zos.putNextEntry(new ZipEntry("notes.json"));
                zos.write(notesJson.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                JSONArray arr = new JSONArray(notesJson);
                File dir = attestDir();
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray files = arr.getJSONObject(i).optJSONArray("files");
                    if (files == null) continue;
                    for (int j = 0; j < files.length(); j++) {
                        String stored = files.getJSONObject(j).optString("stored", "");
                        if (stored.isEmpty()) continue;
                        File f = new File(dir, stored);
                        if (!f.exists()) continue;
                        zos.putNextEntry(new ZipEntry("attachments/" + stored));
                        InputStream in = new java.io.FileInputStream(f);
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
                        in.close();
                        zos.closeEntry();
                    }
                }
                zos.finish();
                zos.close();
                pendingBytes = bos.toByteArray();
                pendingMime = "application/zip";
                pendingName = fileName;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/zip");
                i.putExtra(Intent.EXTRA_TITLE, fileName);
                startActivityForResult(i, REQ_CREATE);
                return true;
            } catch (Exception e) {
                toast("打包失败：" + e.getMessage());
                return false;
            }
        }

        /** 导出纯文本（CSV / JSON），同样走系统保存框。 */
        @JavascriptInterface
        public boolean exportText(String content, String fileName, String mime) {
            try {
                pendingBytes = content.getBytes(StandardCharsets.UTF_8);
                pendingMime = mime == null ? "text/plain" : mime;
                pendingName = fileName;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(pendingMime);
                i.putExtra(Intent.EXTRA_TITLE, fileName);
                startActivityForResult(i, REQ_CREATE);
                return true;
            } catch (Exception e) {
                toast("导出失败：" + e.getMessage());
                return false;
            }
        }

        /** 选一个备份文件（zip/json/csv）读回来。 */
        @JavascriptInterface
        public boolean pickBackup() {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_IMPORT);
                return true;
            } catch (Exception e) {
                toast("打不开文件选择器");
                return false;
            }
        }

        @JavascriptInterface
        public String saveAttachment(String fileName, String base64) {
            try {
                File dir = attestDir();
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

        @JavascriptInterface
        public boolean openAttachment(String storedName) {
            try {
                File f = new File(attestDir(), storedName);
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

        @JavascriptInterface
        public boolean deleteAttachment(String storedName) {
            try {
                File f = new File(attestDir(), storedName);
                return f.exists() && f.delete();
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean pickFolder() {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_TREE);
                return true;
            } catch (Exception e) {
                toast("无法选择文件夹：" + e.getMessage());
                return false;
            }
        }

        @JavascriptInterface
        public boolean openDownloads() {
            String[] candidates = {
                "content://com.android.externalstorage.documents/root/primary",
                "content://com.android.externalstorage.documents/document/primary%3ADownload"
            };
            for (String c : candidates) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setData(Uri.parse(c));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return true;
                } catch (Exception ignored) { }
            }
            return false;
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
