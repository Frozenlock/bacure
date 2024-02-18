# Changelog
## Unreleased

## [1.2.1] - 2024-02-18
- Fix lazyness bug in `discover-network`: was sending multiple `Who-Is` simultaneously.
- `boot-up!` returns the device ID.
- (Private/impl) Introduce per-device threadpool for better network control.

## [1.2.0] - 2024-01-21
- `with-temp-devices` no longer terminates and restarts the local device.
- Add `local-test-devices!` and `local-registered-test-devices!` as public test helpers.

## [1.1.10] - 2022-11-26
- Fix `read-range-request` to allow having a nil array index;
- Check if device is initialized before terminating it;
- Fix `test-device-object-property-reference` test: use device object;
- Allow additional properties when creating objects;
- Ability to create objects by only providing `:object-type`;
- Fix bug in `find-bacnet-port` where temporary devices would stay around;
- Update dependencies, including BACnet4J 6.0.0;
- Use logging instead of simply printing statements.

## [1.1.9] - 2021-01-22
- Fix bug where a nil `:device-id` could be returned by `local-device-backup`.

## [1.1.8] - 2020-09-17
- Enforce distinct `object-property-references` in property reading functions. This should make reading from devices returning duplicates in their object-list (bug in their implementation?) more reliable.

## [1.1.7] - 2019-12-12
- Fix bug where `boot-up!` would create a device with a different device-id than the one provided.

## [1.1.6] - 2019-12-05
- Add coercion for `write-property-multiple-error`.
  (A write request with this error would timeout instead of immediately return the error.)

## [1.1.5] - 2019-11-14
- Allow sequential properties to be nil.

## [1.1.4] - 2019-11-13
- Fix property encoding for sequences (array/collection/list) such as weekly-schedule.

## [1.1.3] - 2019-10-12
- Update `:import` form to work with Clojure 1.10

## [1.1.2] - 2019-07-28
- Remove log4j12 from dependency (it's only a dev dependency).

## [1.1.1] - 2019-07-19
- Update BACnet4J to 5.0.0-1;
- Add MSTP support! (Thanks to Alex Whitt);
- Don't offer potential interfaces without a broadcast address (should avoid problems with OpenVPN);
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
