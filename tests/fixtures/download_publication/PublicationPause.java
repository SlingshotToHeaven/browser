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

import java.util.List;

/** Host-only debugger for the real copy loop and pre-commit publication boundary. */
public final class PublicationPause {
    private static final String TASK = "com.privacy.downloader.engine.DownloadTask";
    private static final String FILE = "com.privacy.downloader.engine.FilePublication";
    private static final String CUT = "cut";

    private record Boundary(String owner, String name, String signature, int line, int count, String marker) {}

    private static final Boundary PRE_COMMIT = new Boundary(TASK, "persistPublication",
            "(Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;JLjava/lang/String;)V", 0, 1, "HELD_BEFORE_DB_WRITE");

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS"))
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
