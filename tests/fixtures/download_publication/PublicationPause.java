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

/** Host-only debugger: stop at the first instruction of the real DB-write wrapper. */
public final class PublicationPause {
    private static final String CLASS = "com.privacy.downloader.engine.DownloadTask";
    private static final String METHOD = "persistPublication";
    private static final String SIGNATURE = "(Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;JLjava/lang/String;)V";

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS"))
                || !"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))) {
            throw new IllegalStateException("Only attach to the disposable CI emulator");
        }
        int port = Integer.parseInt(args[0]);
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid local forward port");
        AttachingConnector socket = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(connector -> connector.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var arguments = socket.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1");
        arguments.get("port").setValue(Integer.toString(port));
        arguments.get("timeout").setValue("10000");
        VirtualMachine vm = socket.attach(arguments);

        vm.suspend();
        var prepared = vm.classesByName(CLASS).stream().filter(ReferenceType::isPrepared).toList();
        if (prepared.isEmpty()) {
            var watch = vm.eventRequestManager().createClassPrepareRequest();
            watch.addClassFilter(CLASS);
            watch.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            watch.enable();
        } else {
            if (prepared.size() != 1) throw new IllegalStateException("Ambiguous download task class");
            arm(vm, prepared.get(0));
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
                        arm(vm, prepare.referenceType());
                    } else if (event instanceof BreakpointEvent hit) {
                        Method caller = hit.thread().frame(1).location().method();
                        if (!caller.declaringType().name().equals(CLASS) || !caller.name().equals("publishOutput")) {
                            throw new IllegalStateException("Wrong caller at the publication boundary");
                        }
                        held = true;
                        System.out.println("HELD_BEFORE_DB_WRITE");
                    } else if (event instanceof VMDisconnectEvent) {
                        if (!held) throw new IllegalStateException("App disconnected before the publication boundary");
                        System.out.println("DISCONNECTED_AFTER_HOLD");
                        return;
                    }
                }
                // Do not resume anything after the cut. The host will force-stop the process.
                if (!held) events.resume();
            }
        } catch (VMDisconnectedException disconnected) {
            if (!held) throw disconnected;
            System.out.println("DISCONNECTED_AFTER_HOLD");
        }
    }

    private static void arm(VirtualMachine vm, ReferenceType type) {
        var methods = type.methodsByName(METHOD, SIGNATURE);
        if (methods.size() != 1 || methods.get(0).location() == null || methods.get(0).location().codeIndex() != 0) {
            throw new IllegalStateException("No exact bytecode entry for the publication boundary");
        }
        var breakpoint = vm.eventRequestManager().createBreakpointRequest(methods.get(0).location());
        breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        breakpoint.addCountFilter(1);
        breakpoint.enable();
    }
}
