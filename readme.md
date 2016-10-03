# Bacure

A clojure library wrapping the excellent BACnet4J library. Hopefully
it will be able to abstract some of the ugly BACnet details
(datatypes) and provide a seamless Clojure experience.


## Usage

Add the following dependency in your `project.clj`:

[![Clojars Project](http://clojars.org/bacure/latest-version.svg)](http://clojars.org/bacure)

Check the
[documentation](http://frozenlock.github.io/bacure/index.html)
for the available functions with their descriptions.


### Getting Started

To spin up a BACnet device, you can use `bacure.core/boot-up!`.
This will create a new local device, bind it to a network interface
and search for other BACnet devices.

If you need to specify an interface (for example if you have an
Ethernet port AND a Wifi on your computer), use the broadcast address
associated with the interface.

```clj

(bacure.core/boot-up! {:broadcast-address "192.168.1.255"
                       :device-id 3333
					   :object-name "My awesome BACnet device"})

```

### Multiple local devices

It is now possible to have more than a local device in memory,
identified by their BACnet IDs. For example, one could have device
1338 with a broadcast address of 192.168.1.255 and device 1339 with a
broadcast address of 172.180.255.255. Unless the device ID is given as
an argument, most functions will act with the first local device they
can find.

```clj

; the following returns the remote devices for the local device 1339
(bacure.remote-device/remote-devices 1339)

; => #{10100, 10200}

```


### Data coercion

Higher level functions will automatically convert clojure
datastructure to the correct bacnet4j java type, so you shouldn't have
to worry about that. However, if for some reason you need to do the
conversions manually, you can use the coercion functions as such :

```clj
(require '[bacure.coerce :as c])

;; convert from clojure to bacnet4j :

(c/clojure->bacnet :unsigned-integer 10)

; => #object[com.serotonin.bacnet4j.type.primitive.UnsignedInteger 0x5b2efe25 "10"]


;; convert from bacnet4j to clojure

(def aaa (c/clojure->bacnet :unsigned-integer 10)) ;; first we create a bacnet4j object


(c/bacnet->clojure aaa)

; => 10

```

If you don't know what kind of data is expected, you can use the
`example` function :

```clj

(c/example :object-identifier)

; => [:analog-input 0]

```



## Changelogs

### 1.0.8
- Bugfix in underlying BACnet4J library : endless loop on segmented
  communication error.

### 1.0.6
- Better communication with slow networks and/or large requests.

### 1.0.4 - 1.0.5
- Add coercion for BaseError type;
- Handle `property` error. (Example: unknown-property).

### 1.0.2 - 1.0.3
- Fix segmentation fallback
- Longer delay between broadcasts (initialization)

### 1.0.1
Minor changes.

- use forked BACnet4j version;
- re-add the function `is-alive?`;
- add funtion to write multiple properties;
- handle 'rejects' error;
- fix `encode-properties` function;
- add function to read trendlogs.

### 1.0

Major changes.

It is expected that you will have to change a few function names if
you are transistionning from 0.6 or below. You can use the
[documentation](http://frozenlock.github.io/bacure/index.html) to help
you find the new function names.


- Data coercion into BACnet4J objects have been redone from scratch;
- Support multiple local devices;
- Removed the functions related to local objects;
- Removed caching for read-properties (shouldn't be up to Bacure.)

## License

Copyright © 2016 Frozenlock

GNU General Public License version 3.0 (GPLv3)
