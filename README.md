# Silence

Silence (formerly SMSSecure) is an Android SMS/MMS application that can establish end-to-end encrypted conversations with other Silence users. It can also send and receive ordinary, unencrypted SMS/MMS messages.

The historical F-Droid build is outdated, and the former Google Play listing is no longer available. Build the current source using [BUILDING.md](BUILDING.md).

Features:

* Easy. Silence works as an SMS/MMS application without requiring an account or a dedicated messaging service.
* Flexible. Communicate with other Silence users through encrypted sessions and with everyone else through ordinary SMS/MMS.
* Private. Secure sessions use the Signal protocol for end-to-end encryption; mobile carriers still handle message transport and metadata.
* Safe. Silence supports local message and key encryption as well as encrypted backups.
* Open Source. Silence is Free and Open Source, enabling anyone to verify its security by auditing the code.

## Project goals

This is a fork of [TextSecure](https://github.com/WhisperSystems/TextSecure) (now Signal) that aims to keep the SMS encryption that TextSecure removed [for a variety of reasons](https://whispersystems.org/blog/goodbye-encrypted-sms/).

Silence focuses on SMS and MMS. This fork aims to:

* Keep SMS/MMS encryption
* Drop Google services dependencies (push messages are not available in Silence)
* Preserve compatibility with existing Silence conversations while modernizing the application and its cryptography

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to contribute code, translations, or bug reports.

Instructions for setting up a development environment and building Silence are in [BUILDING.md](BUILDING.md).

## Support

Use [GitHub issues](https://github.com/zdev-code/silencesms/issues) to report bugs or request help.

## Legal

### Cryptography Notice

This distribution includes cryptographic software. The country in which you currently reside may have restrictions on the import, possession, use, and/or re-export to another country, of encryption software.
BEFORE using any encryption software, please check your country's laws, regulations and policies concerning the import, possession, or use, and re-export of encryption software, to see if this is permitted.
See the [Wassenaar Arrangement](https://www.wassenaar.org/) for more information.

### License

Licensed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).
