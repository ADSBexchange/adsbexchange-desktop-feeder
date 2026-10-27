# Security

Please report security problems privately, not in a public issue: use this repository's
**Security** tab, then **Report a vulnerability**
(https://github.com/ADSBexchange/adsbexchange-desktop-feeder/security/advisories/new). Include what
you found, how to reproduce it, and the app version (Settings > About).

We acknowledge reports within 5 business days. Once a fix is released, we publish a security
advisory for the vulnerability and credit the reporter, unless they'd rather not be named. Please
give us 90 days to release a fix before disclosing the vulnerability publicly.

Of particular interest:

- anything that could run code on a feeder's PC, especially through the elevated driver helper
  (`rtlsdr-driver.exe`) or readsb, which parses radio input anyone can transmit;
- anything that leaks a feeder's precise location or feeder ID beyond what the README's Privacy
  section describes;
- a way for another program on the PC to reach the receiver position or impersonate readsb.

For help with the app itself, ask on ADS-B Exchange's Discord server (Settings > Help).
