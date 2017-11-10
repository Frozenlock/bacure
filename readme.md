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

#### Configuration Details

  The optional config map can contain the following:

| Keyword                         | Network type | Type              | Default              | Description                                                                                                                                                                                                                                                                                                                                                                              |
| ------------------------------- | ------------ | ----------------- | -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| :network-type                   | N/A          | :ipv4 or :mstp    | :ipv4                | Specifies which type of network to create. Other config keywords may or may not be needed based on this value.                                                                                                                                                                                                                                                                           |
| :device-id                      | All          | number            | 1338                 | The device identifier on the network. It should be unique.                                                                                                                                                                                                                                                                                                                               |
| :other-configs                  | All          | anything          | nil                  | Any configuration returned when using the function 'get-configs'. These configuation can be set when the device is created simply by providing them in the arguments. For example, to change the vendor name, simply add '{:vendor-name \"some vendor name\"}'.                                                                                                                          |
| :model-name                     | All          | string            | "Bacure"             |                                                                                                                                                                                                                                                                                                                                                                                          |
| :vendor-identifier              | All          | number            | 697                  |                                                                                                                                                                                                                                                                                                                                                                                          |
| :apdu-timeout                   | All          | number            | 6000                 | Time in milliseconds                                                                                                                                                                                                                                                                                                                                                                     |
| :number-of-apdu-retries         | All          | number            | 2                    |                                                                                                                                                                                                                                                                                                                                                                                          |
| :description                    | All          | string            | <see 
local_device.clj> |                                                                                                                                                                                                                                                                                                                                                                                          |
| :vendor-name                    | All          | string            | "HVAC.IO"            |                                                                                                                                                                                                                                                                                                                                                                                          |
| :broadcast-address              | :ipv4        | string            | <generated>          | The address on which we send 'WhoIs'. You should not have to provide anything for this, unless you have multiple interfaces or want to trick your machine into sending to a 'fake' broadcast address.                                                                                                                                                                                    |
| :port                           | :ipv4        | string            | 47808                | The BACnet port, usually 47808.                                                                                                                                                                                                                                                                                                                                                          |
| :local-address                  | :ipv4        | string            | 0.0.0.0              | The default, \"0.0.0.0\", is also known as the 'anylocal'. (Default by the underlying BACnet4J library.) This is required on Linux, Solaris and some Windows machines in order to catch packet sent as a broadcast. You can manually change it, but unless you know exactly what you are doing, bad things will happen.                                                                  |
| :com-port                       | :mstp        | string            | <required for :mstp> | On Linux, this is usually /dev/ttyS[0-9] or /dev/ttyUSB[0-9]. For Windows, it's COM[0-9]*. You can use `(serial.util/list-ports)` to see what you have. (On Linux, make sure you have appropriate permissions, or you'll get "port in use" errors. I usually just change the permissions to 666, using  something like this: https://bbs.archlinux.org/viewtopic.php?pid=676380#p676380) |
| :baud-rate                      | :mstp        | number            | 115200               | Supported speeds are 1200, 2400, 4800, 9600, 19200, 38400, 57600 and 115200 bits per second.                                                                                                                                                                                                                                                                                             |
| :databits                       | :mstp        | DATABITS_*        | DATABITS_8           | These live in serial.core                                                                                                                                                                                                                                                                                                                                                                |
| :stopbits                       | :mstp        | STOPBITS_*        | STOPBITS_1           | These live in serial.core                                                                                                                                                                                                                                                                                                                                                                |
| :parity                         | :mstp        | PARITY_*          | PARITY_NONE          | These live in serial.core                                                                                                                                                                                                                                                                                                                                                                |
| :mstp-config                    | :mstp        | map               | nil                  | Additional MSTP configuration. The below keys are included:                                                                                                                                                                                                                                                                                                                              |
| [:mstp-config :node-type]       | :mstp        | :master 
or :slave | :master              | Only Master nodes can initiate requests and pass tokens. Slave nodes can only respond to requests, and are not part of the token-passing ring. See http://store.chipkin.com/articles/how-does-bacnet-mstp-discover-new-devices-on-a-network for more information.                                                                                                                        |
| [:mstp-config :node-id]         | :mstp        | number            | 1                    | Must be unique on the network. (The MAC address must also be unique, by the way!)                                                                                                                                                                                                                                                                                                        |
| [:mstp-config :debug-traffic]   | :mstp        | boolean           | false                | Turns on some very chatty logging in the REPL or console, which spits out all frames in and out. One of several debugging outlets, unfortunately.                                                                                                                                                                                                                                        |
| [:mstp-config :retry-count]     | :mstp        | number            | 3                    | Relevant for Master nodes only. Specifies the number of transmission retries used for Token and Poll For Master transmissions.                                                                                                                                                                                                                                                           |
| [:mstp-config :max-info-frames] | :mstp        | number            | 8                    | Specifies how many messages the controller can send out to other controllers when it has the token on the network.                                                                                                                                                                                                                                                                       |
| [:mstp-config :max-master-id]   | :mstp        | number            | 127                  | Highest node-id to search when doing Poll-for-Master. It's optimal to have your node-ids close to each other, and to set this value to no higher than your highest node-id.                                                                                                                                                                                                              |

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
