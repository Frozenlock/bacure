# Changelog

## 1.1.1
- Update BACnet4J to 5.0.0-1;
- Add MSTP support! (Thanks to Alex Whitt);
- Don't offer potentital interfaces without a broadcast address (should avoid problems with OpenVPN);
- More reliable fallbacks when communicating with remote devices.

## 1.0.8
- Bugfix in underlying BACnet4J library : endless loop on segmented
  communication error.

## 1.0.6
- Better communication with slow networks and/or large requests.

## 1.0.4 - 1.0.5
- Add coercion for BaseError type;
- Handle `property` error. (Example: unknown-property).

## 1.0.2 - 1.0.3
- Fix segmentation fallback
- Longer delay between broadcasts (initialization)

## 1.0.1
Minor changes.

- use forked BACnet4j version;
- re-add the function `is-alive?`;
- add funtion to write multiple properties;
- handle 'rejects' error;
- fix `encode-properties` function;
- add function to read trendlogs.

## 1.0

Major changes.

It is expected that you will have to change a few function names if
you are transistionning from 0.6 or below. You can use the
[documentation](http://frozenlock.github.io/bacure/index.html) to help
you find the new function names.


- Data coercion into BACnet4J objects have been redone from scratch;
- Support multiple local devices;
- Removed the functions related to local objects;
- Removed caching for read-properties (shouldn't be up to Bacure.)
