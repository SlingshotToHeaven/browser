import com.sun.jdi.Bootstrap;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.EventRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Host-only debugger for the real copy loop and pre-commit publication boundary. */
public final class PublicationPause {
    private static final String TASK = "com.privacy.downloader.engine.DownloadTask";
    private static final String FILE = "com.privacy.downloader.engine.FilePublication";
    private static final String CUT = "cut";

    private record Boundary(String owner, String name, String signature, int line, int count, String marker) {}

    private static final Boundary PRE_COMMIT = new Boundary(TASK, "persistPublication",
            "(Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;JLjava/lang/String;)V", 0, 1, "HELD_BEFORE_DB_WRITE");

    private static final String PACKAGE = "com.browser";
    private static final String TOKEN_ENV = "BROWSER_DOWNLOAD_LAB_TOKEN";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--arm64-download-lab".equals(args[0])) {
            if (args.length != 6 && args.length != 8) throw new IllegalArgumentException("Invalid physical publication target");
            verifyPhysicalLab(args[1], args[2], args[3], args[4], args[5]);
            args = Arrays.copyOfRange(args, 5, args.length);
        } else if (!"true".equals(System.getenv("GITHUB_ACTIONS"))
                || !"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))) {
            throw new IllegalStateException("Only attach to the disposable CI emulator");
        }
        if (args.length != 1 && args.length != 3) throw new IllegalArgumentException("port [copy line count]");
        int port = Integer.parseInt(args[0]);
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid local forward port");
        List<Boundary> cuts = List.of(PRE_COMMIT);
        if (args.length == 3) {
            int line = Integer.parseInt(args[1]);
            int count = Integer.parseInt(args[2]);
            if (line <= 0 || count <= 0 || count > 64) throw new IllegalArgumentException("Invalid copy cut");
            cuts = List.of(PRE_COMMIT, new Boundary(FILE, "copyAndVerify",
                    "(JLjava/lang/String;Ljava/util/function/BooleanSupplier;)V", line, count, "HELD_DURING_COPY"));
        }
        AttachingConnector socket = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(connector -> connector.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var arguments = socket.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1");
        arguments.get("port").setValue(Integer.toString(port));
        arguments.get("timeout").setValue("10000");
        VirtualMachine vm = socket.attach(arguments);

        vm.suspend();
        for (Boundary cut : cuts) {
            var prepared = vm.classesByName(cut.owner()).stream().filter(ReferenceType::isPrepared).toList();
            if (prepared.isEmpty()) {
                var watch = vm.eventRequestManager().createClassPrepareRequest();
                watch.addClassFilter(cut.owner());
                watch.putProperty(CUT, cut);
                watch.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                watch.enable();
            } else {
                if (prepared.size() != 1) throw new IllegalStateException("Ambiguous publication class");
                arm(vm, prepared.get(0), cut);
            }
        }
        vm.resume();
        System.out.println("READY");

        boolean held = false;
        try {
            for (;;) {
                EventSet events = vm.eventQueue().remove();
                for (var event : events) {
                    if (event instanceof ClassPrepareEvent prepare) {
                        prepare.request().disable();
                        arm(vm, prepare.referenceType(), (Boundary) prepare.request().getProperty(CUT));
                    } else if (event instanceof BreakpointEvent hit) {
                        Boundary cut = (Boundary) hit.request().getProperty(CUT);
                        int callerDepth = 1;
                        if (cut.line() != 0) {
                            requireMethod(hit.thread().frame(1).location().method(), FILE, "open");
                            callerDepth = 2;
                        }
                        requireMethod(hit.thread().frame(callerDepth).location().method(), TASK, "publishOutput");
                        held = true;
                        System.out.println(cut.marker());
                    } else if (event instanceof VMDisconnectEvent) {
                        if (!held) throw new IllegalStateException("App disconnected before the publication boundary");
                        System.out.println("DISCONNECTED_AFTER_HOLD");
                        return;
                    }
                }
                // Never unwind or resume the production code after the cut.
                if (!held) events.resume();
            }
        } catch (VMDisconnectedException disconnected) {
            if (!held) throw disconnected;
            System.out.println("DISCONNECTED_AFTER_HOLD");
        }
    }

    private static void verifyPhysicalLab(String adb, String serial, String pid, String apkHash, String port) throws Exception {
        if (System.getenv("ADB_SERVER_SOCKET") != null || System.getenv("ANDROID_ADB_SERVER_ADDRESS") != null) {
            throw new IllegalStateException("Physical publication cuts require the default local ADB server");
        }
        String token = System.getenv(TOKEN_ENV);
        if (token == null || !token.matches("[0-9a-f]{32}") || !serial.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,95}")
                || serial.startsWith("emulator-") || !pid.matches("[1-9][0-9]{0,8}") || !apkHash.matches("[0-9a-f]{64}")
                || !port.matches("[1-9][0-9]{3,4}") || Integer.parseInt(port) < 1024 || Integer.parseInt(port) > 65535) {
            throw new IllegalStateException("Missing bounded physical-lab identity");
        }
        if (!"device".equals(adbText(adb, serial, "get-state"))
                || !"0".equals(adbText(adb, serial, "shell", "am", "get-current-user"))
                || !"arm64-v8a".equals(adbText(adb, serial, "shell", "getprop", "ro.product.cpu.abi"))
                || Integer.parseInt(adbText(adb, serial, "shell", "getprop", "ro.build.version.sdk")) < 34
                || !physical(adbText(adb, serial, "shell", "getprop", "ro.kernel.qemu"))
                || !physical(adbText(adb, serial, "shell", "getprop", "ro.boot.qemu"))) {
            throw new IllegalStateException("Physical publication cuts require the selected owner ARM64 lab device");
        }
        String path = adbText(adb, serial, "shell", "pm", "path", "--user", "0", PACKAGE);
        if (!path.matches("package:/data/app/~~[A-Za-z0-9_-]+={0,2}/com[.]browser-[A-Za-z0-9_-]+={0,2}/base[.]apk")) {
            throw new IllegalStateException("Not the single installed lab APK");
        }
        String apk = path.substring("package:".length());
        String digest = adbText(adb, serial, "shell", "run-as", PACKAGE, "sha256sum", apk).split("\\s+", 2)[0];
        String marker = adbText(adb, serial, "shell", "run-as", PACKAGE, "cat", "files/download-device-lab.token");
        String uid = adbText(adb, serial, "shell", "run-as", PACKAGE, "id", "-u");
        String process = adbText(adb, serial, "shell", "ps", "-p", pid, "-o", "PID,UID,NAME");
        if (!apkHash.equals(digest) || !token.equals(marker) || !uid.matches("[1-9][0-9]{4,5}")
                || !pid.equals(adbText(adb, serial, "shell", "pidof", PACKAGE))
                || process.lines().filter(line -> line.strip().replaceAll("\\s+", " ").equals(pid + " " + uid + " " + PACKAGE)).count() != 1) {
            throw new IllegalStateException("Installed APK, lab marker or debuggable process did not match");
        }
        String forward = serial + " tcp:" + port + " jdwp:" + pid;
        if (adbText(adb, serial, "forward", "--list").lines().filter(forward::equals).count() != 1) {
            throw new IllegalStateException("No matching local forward to the verified lab PID");
        }
    }

    private static boolean physical(String qemu) {
        return qemu.isEmpty() || "0".equals(qemu);
    }

    private static String adbText(String adb, String serial, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of(adb, "-s", serial));
        cmd.addAll(List.of(args));
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("Could not verify the exact physical lab target");
            }
            byte[] output = process.getInputStream().readNBytes(16385);
            if (output.length > 16384) throw new IOException("Unexpected lab identity output");
            return new String(output, StandardCharsets.US_ASCII).strip();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void requireMethod(Method method, String owner, String name) {
        if (!method.declaringType().name().equals(owner) || !method.name().equals(name)) {
            throw new IllegalStateException("Wrong caller at the publication boundary");
        }
    }

    private static void arm(VirtualMachine vm, ReferenceType type, Boundary cut) throws Exception {
        var methods = type.methodsByName(cut.name(), cut.signature());
        if (methods.size() != 1) throw new IllegalStateException("No unique publication method: " + cut.name());
        var method = methods.get(0);
        var locations = cut.line() == 0 ? List.of(method.location()) : method.locationsOfLine(cut.line());
        if (locations.size() != 1 || (cut.line() == 0 && locations.get(0).codeIndex() != 0)) {
            throw new IllegalStateException("No exact bytecode location for " + cut.name() + ":" + cut.line());
        }
        var breakpoint = vm.eventRequestManager().createBreakpointRequest(locations.get(0));
        breakpoint.putProperty(CUT, cut);
        breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        breakpoint.addCountFilter(cut.count());
        breakpoint.enable();
    }
}
