#!/usr/bin/env python3
"""Retired entry point. It does not listen, probe device files, or start a provider."""

import sys


def main():
    print("The browser's Termux sidecar has been retired. No service was started. "
          "Stop any older sidecar you still have running and choose a supported provider in Agent settings.",
          file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
