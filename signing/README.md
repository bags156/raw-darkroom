# Signing key

`public-release.p12` is a **public** signing key (store and key password `android`, alias
`rawdarkroom`). It is committed on purpose so that every build, from CI or any machine, is signed
the same way and installs over the previous one without uninstalling.

The trade-off: the signature proves nothing about who built an APK. Only install builds you got
from this repository's own Actions runs. If this app is ever distributed more widely, switch to a
private key kept in repository secrets.
