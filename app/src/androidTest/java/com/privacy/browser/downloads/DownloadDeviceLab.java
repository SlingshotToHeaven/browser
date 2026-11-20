package com.privacy.browser.downloads;

import android.app.Instrumentation;
import android.os.Build;
import android.os.Process;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assume.assumeTrue;

/** Opt in only on the fresh debug install prepared by the Downloads lab driver. */
public final class DownloadDeviceLab {
    public static final String MODE = "arm64-download-lab";
    private DownloadDeviceLab() {}

    public static boolean requested() {
        return MODE.equals(InstrumentationRegistry.getArguments().getString("download_batch"));
    }

    public static void requireProfile(Instrumentation ins, Class<?> testClass, String method,
                                      String kernelQemu, String bootQemu) throws IOException {
        var args = InstrumentationRegistry.getArguments();
        var app = ins.getTargetContext().getApplicationContext();
        String token = args.getString("download_lab_token", "");
        Path marker = app.getFilesDir().toPath().resolve("download-device-lab.token");
        assumeTrue("Select exactly one test on the dedicated physical Downloads profile",
                requested() && "com.browser".equals(app.getPackageName())
                        && (testClass.getName() + "#" + method).equals(args.getString("class"))
                        && token.matches("[0-9a-f]{32}")
                        && Build.VERSION.SDK_INT >= 34 && Process.is64Bit()
                        && Build.SUPPORTED_ABIS.length > 0 && "arm64-v8a".equals(Build.SUPPORTED_ABIS[0])
                        && physical(kernelQemu) && physical(bootQemu)
                        && Files.isRegularFile(marker) && Files.size(marker) == token.length()
                        && token.equals(Files.readString(marker)));
    }

    private static boolean physical(String qemu) {
        String value = qemu.trim();
        return value.isEmpty() || "0".equals(value);
    }
}
