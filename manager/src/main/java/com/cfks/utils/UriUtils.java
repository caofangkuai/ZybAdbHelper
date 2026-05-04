package com.cfks.utils;

import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import androidx.core.content.FileProvider;
import java.io. *;
import java.lang.reflect.Array;
import java.lang.reflect.Method;

import roro.stellar.manager.StellarApplication;

public final class UriUtils {
   private UriUtils() {
      throw new UnsupportedOperationException("u can't instantiate me...");
   }

   public static File uri2File(Uri uri) {
      if (uri == null) {
         return null;
      }
      File uri2FileReal = uri2FileReal(uri);
      return uri2FileReal == null ? copyUri2Cache(uri) : uri2FileReal;
   }

   private static File uri2FileReal(Uri uri) {
      Uri uri2;
      File fileFromUri = null;
      String str;
      Log.d("UriUtils", uri.toString());
      String authority = uri.getAuthority();
      String scheme = uri.getScheme();
      String path = uri.getPath();
      if (Build.VERSION.SDK_INT >= 24 && path != null) {
         for (String str2 : new String[]{"/external/", "/external_path/"}) {
            if (path.startsWith(str2)) {
               File file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + path.replace(str2, "/"));
               if (file.exists()) {
                  Log.d("UriUtils", uri.toString() + " -> " + str2);
                  return file;
               }
            }
         }
         File file2 = null;
         if (path.startsWith("/files_path/")) {
            file2 = new File(StellarApplication.getApp().getFilesDir().getAbsolutePath() + path.replace("/files_path/", "/"));
         } else if (path.startsWith("/cache_path/")) {
            file2 = new File(StellarApplication.getApp().getCacheDir().getAbsolutePath() + path.replace("/cache_path/", "/"));
         } else if (path.startsWith("/external_files_path/")) {
            file2 = new File(StellarApplication.getApp().getExternalFilesDir(null).getAbsolutePath() + path.replace("/external_files_path/", "/"));
         } else if (path.startsWith("/external_cache_path/")) {
            file2 = new File(StellarApplication.getApp().getExternalCacheDir().getAbsolutePath() + path.replace("/external_cache_path/", "/"));
         }
         if (file2 != null && file2.exists()) {
            Log.d("UriUtils", uri.toString() + " -> " + path);
            return file2;
         }
      }
      if ("file".equals(scheme)) {
         if (path != null) {
            return new File(path);
         }
         Log.d("UriUtils", uri.toString() + " parse failed. -> 0");
         return null;
      }
      if (Build.VERSION.SDK_INT >= 19 && DocumentsContract.isDocumentUri(StellarApplication.getApp(), uri)) {
         if ("com.android.externalstorage.documents".equals(authority)) {
            String[] split = DocumentsContract.getDocumentId(uri).split(":");
            String str3 = split[0];
            if ("primary".equalsIgnoreCase(str3)) {
               return new File(Environment.getExternalStorageDirectory() + "/" + split[1]);
            }
            StorageManager storageManager = (StorageManager) StellarApplication.getApp().getSystemService("storage");
            try {
               Class<?> cls = Class.forName("android.os.storage.StorageVolume");
               Method method = storageManager.getClass().getMethod("getVolumeList", new Class[0]);
               Method method2 = cls.getMethod("getUuid", new Class[0]);
               Method method3 = cls.getMethod("getState", new Class[0]);
               Method method4 = cls.getMethod("getPath", new Class[0]);
               Method method5 = cls.getMethod("isPrimary", new Class[0]);
               Method method6 = cls.getMethod("isEmulated", new Class[0]);
               Object invoke = method.invoke(storageManager, new Object[0]);
               int length = Array.getLength(invoke);
               for (int i = 0; i < length; i++) {
                  Object obj = Array.get(invoke, i);
                  if (("mounted".equals(method3.invoke(obj, new Object[0])) || "mounted_ro".equals(method3.invoke(obj, new Object[0]))) && ((!((Boolean) method5.invoke(obj, new Object[0])).booleanValue() || !((Boolean) method6.invoke(obj, new Object[0])).booleanValue()) && (str = (String) method2.invoke(obj, new Object[0])) != null && str.equals(str3))) {
                     return new File(method4.invoke(obj, new Object[0]) + "/" + split[1]);
                  }
               }
            } catch (Exception e) {
               Log.d("UriUtils", uri.toString() + " parse failed. " + e.toString() + " -> 1_0");
            }
            Log.d("UriUtils", uri.toString() + " parse failed. -> 1_0");
            return null;
         }
         if ("com.android.providers.downloads.documents".equals(authority)) {
            String documentId = DocumentsContract.getDocumentId(uri);
            if (TextUtils.isEmpty(documentId)) {
               Log.d("UriUtils", uri.toString() + " parse failed(id is null). -> 1_1");
               return null;
            }
            if (documentId.startsWith("raw:")) {
               return new File(documentId.substring(4));
            }
            try {
               long parseLong = Long.parseLong(documentId.startsWith("msf:") ? documentId.split(":")[1] : documentId);
               for (String str4 : new String[]{"content://downloads/public_downloads", "content://downloads/all_downloads", "content://downloads/my_downloads"}) {
                  try {
                     fileFromUri = getFileFromUri(ContentUris.withAppendedId(Uri.parse(str4), parseLong), "1_1");
                  } catch (Exception e2) {
                  }
                  if (fileFromUri != null) {
                     return fileFromUri;
                  }
               }
               Log.d("UriUtils", uri.toString() + " parse failed. -> 1_1");
               return null;
            } catch (Exception e3) {
               return null;
            }
         }
         if ("com.android.providers.media.documents".equals(authority)) {
            String[] split2 = DocumentsContract.getDocumentId(uri).split(":");
            String str5 = split2[0];
            if ("image".equals(str5)) {
               uri2 = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            } else if ("video".equals(str5)) {
               uri2 = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            } else if ("audio".equals(str5)) {
               uri2 = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            } else {
               Log.d("UriUtils", uri.toString() + " parse failed. -> 1_2");
               return null;
            }
            return getFileFromUri(uri2, "_id=?", new String[]{split2[1]}, "1_2");
         }
         if ("content".equals(scheme)) {
            return getFileFromUri(uri, "1_3");
         }
         Log.d("UriUtils", uri.toString() + " parse failed. -> 1_4");
         return null;
      }
      if ("content".equals(scheme)) {
         return getFileFromUri(uri, "2");
      }
      Log.d("UriUtils", uri.toString() + " parse failed. -> 3");
      return null;
   }

   private static File getFileFromUri(Uri uri, String str) {
      return getFileFromUri(uri, null, null, str);
   }

   private static File getFileFromUri(Uri uri, String str, String[] strArr, String str2) {
      File file = null;
      if ("com.google.android.apps.photos.content".equals(uri.getAuthority())) {
         if (!TextUtils.isEmpty(uri.getLastPathSegment())) {
            return new File(uri.getLastPathSegment());
         }
      } else if ("com.tencent.mtt.fileprovider".equals(uri.getAuthority())) {
         String path = uri.getPath();
         if (!TextUtils.isEmpty(path)) {
            return new File(Environment.getExternalStorageDirectory(), path.substring("/QQBrowser".length(), path.length()));
         }
      } else if ("com.huawei.hidisk.fileprovider".equals(uri.getAuthority())) {
         String path2 = uri.getPath();
         if (!TextUtils.isEmpty(path2)) {
            return new File(path2.replace("/root", ""));
         }
      }
      Cursor query = StellarApplication.getApp().getContentResolver().query(uri, new String[]{"_data"}, str, strArr, null);
      if (query == null) {
         Log.d("UriUtils", uri.toString() + " parse failed(cursor is null). -> " + str2);
         return null;
      }
      try {
         if (query.moveToFirst()) {
            int columnIndex = query.getColumnIndex("_data");
            if (columnIndex > - 1) {
               File file2 = new File(query.getString(columnIndex));
               query.close();
               file = file2;
            } else {
               Log.d("UriUtils", uri.toString() + " parse failed(columnIndex: " + columnIndex + " is wrong). -> " + str2);
            }
         } else {
            Log.d("UriUtils", uri.toString() + " parse failed(moveToFirst return false). -> " + str2);
            query.close();
         }
         return file;
      } catch (Exception e) {
         Log.d("UriUtils", uri.toString() + " parse failed. -> " + str2);
         return file;
      } finally {
         query.close();
      }
   }

   /* JADX WARN: Multi-variable type inference failed */
   /* JADX WARN: Removed duplicated region for block: B:31:0x0060 A[EXC_TOP_SPLITTER, SYNTHETIC] */
   /* JADX WARN: Type inference failed for: r2v0, types: [java.lang.String] */
   /* JADX WARN: Type inference failed for: r2v1 */
   /* JADX WARN: Type inference failed for: r2v10 */
   /* JADX WARN: Type inference failed for: r2v11 */
   /* JADX WARN: Type inference failed for: r2v12 */
   /* JADX WARN: Type inference failed for: r2v13 */
   /* JADX WARN: Type inference failed for: r2v3, types: [java.io.InputStream] */
   /* JADX WARN: Type inference failed for: r2v4 */
   /* JADX WARN: Type inference failed for: r2v6 */
   /* JADX WARN: Type inference failed for: r2v8 */
   /* JADX WARN: Type inference failed for: r2v9 */
   /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
   private static File copyUri2Cache(Uri uri) {
      InputStream inputStream;
      Exception e;
      InputStream r2 = null;
      Throwable th;
      File file = null;
      Log.d("UriUtils", "copyUri2Cache() called");
      try {
         try {
            inputStream = StellarApplication.getApp().getContentResolver().openInputStream(uri);
            try {
               file = new File(StellarApplication.getApp().getCacheDir(), "" + System.currentTimeMillis());
               FileIOUtils.writeFileFromIS(file.getAbsolutePath(), inputStream);
               r2 = inputStream;
               if (inputStream != null) {
                  try {
                     inputStream.close();
                     r2 = inputStream;
                  } catch (IOException e1145) {
                     e1145.printStackTrace();
                     r2 = inputStream;
                  }
               }
            } catch (Exception e2) {
               e = e2;
               e.printStackTrace();
               if (inputStream != null) {
                  try {
                     inputStream.close();
                     file = null;
                     r2 = inputStream;
                  } catch (IOException e3) {
                     e3.printStackTrace();
                     file = null;
                     r2 = inputStream;
                  }
               } else {
                  file = null;
                  r2 = inputStream;
               }
               return file;
            }
         } catch (Throwable th27378) {
            th = th27378;
            if (r2 != null) {
               try {
                  r2.close();
               } catch (IOException e4) {
                  e4.printStackTrace();
               }
            }
            throw th;
         }
      } catch (FileNotFoundException e5) {
         e = e5;
         inputStream = null;
      } catch (Throwable th2) {
         th = th2;
         r2 = null;
         if (r2 != null) {
         }
      }
      return file;
   }
   public static byte[] inputStream2Bytes(InputStream inputStream) {
      if (inputStream == null) {
         return null;
      }
      return input2OutputStream(inputStream).toByteArray();
   }
   public static ByteArrayOutputStream input2OutputStream(InputStream inputStream) {
      final int BUFFER_SIZE = 8192;
      try {
         if (inputStream == null) {
            return null;
         }
         try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] bArr = new byte[BUFFER_SIZE];
            while (true) {
               int read = inputStream.read(bArr, 0, BUFFER_SIZE);
               if (read == - 1) {
                  return byteArrayOutputStream;
               }
               byteArrayOutputStream.write(bArr, 0, read);
            }
         } catch (IOException e2) {
            e2.printStackTrace();
            try {
               inputStream.close();
               return null;
            } catch (IOException e3) {
               e3.printStackTrace();
               return null;
            }
         }
      } finally {
         try {
            inputStream.close();
         } catch (IOException e4) {
            e4.printStackTrace();
         }
      }
   }
   public static byte[] uri2Bytes(Uri uri) {
      Throwable th;
      InputStream inputStream;
      byte[] bArr = null;
      try {
         if (uri != null) {
            try {
               inputStream = StellarApplication.getApp().getContentResolver().openInputStream(uri);
               try {
                  bArr = inputStream2Bytes(inputStream);
                  if (inputStream != null) {
                     try {
                        inputStream.close();
                     } catch (IOException e) {
                        e.printStackTrace();
                     }
                  }
               } catch (Exception e2) {
                  e2.printStackTrace();
                  Log.d("UriUtils", "uri to bytes failed.");
                  if (inputStream != null) {
                     try {
                        inputStream.close();
                     } catch (IOException e3) {
                        e3.printStackTrace();
                     }
                  }
                  return bArr;
               }
            } catch (FileNotFoundException e4) {
               inputStream = null;
            } catch (Throwable th2) {
               th = th2;
               inputStream = null;
               if (inputStream != null) {
                  try {
                     inputStream.close();
                  } catch (IOException e5) {
                     e5.printStackTrace();
                  }
               }
               throw th;
            }
         }
         return bArr;
      } catch (Throwable th3) {
         th = th3;
      }
      return bArr;
   }
}