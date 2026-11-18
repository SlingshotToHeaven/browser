import contextlib
import io
import unittest
from unittest.mock import patch

import browser_agent_bridge as bridge


class AgentBridgeSmokeTest(unittest.TestCase):
    def test_retired_entrypoint_does_not_listen_or_spawn_processes(self):
        error = io.StringIO()
        with contextlib.redirect_stderr(error), patch('socket.socket') as socket, patch('subprocess.Popen') as spawn:
            self.assertNotEqual(0, bridge.main())
        socket.assert_not_called()
        spawn.assert_not_called()
        self.assertIn('retired', error.getvalue())
        self.assertIn('No service was started', error.getvalue())
