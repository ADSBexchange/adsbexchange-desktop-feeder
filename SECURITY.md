# Security

Please report security problems privately, not in a public issue: use this repository's
**Security** tab, then **Report a vulnerability**. Include what you found, how to reproduce it, and
the app version (Settings > About).

Of particular interest:

- anything that could run code on a feeder's PC, especially through the elevated driver helper
  (`rtlsdr-driver.exe`) or readsb, which parses radio input anyone can transmit;
- anything that leaks a feeder's precise location or feeder ID beyond what the README's Privacy
  section describes;
- a way for another program on the PC to reach the receiver position or impersonate readsb.

For help with the app itself, ask on ADS-B Exchange's Discord server (Settings > Help).
