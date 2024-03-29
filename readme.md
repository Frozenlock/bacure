# Bacure

A clojure library wrapping the excellent BACnet4J library. Hopefully
it will be able to abstract some of the ugly BACnet details
(datatypes) and provide a seamless Clojure experience.

<!-- markdown-toc start - Don't edit this section. Run M-x markdown-toc-generate-toc again -->
**Table of Contents**

- [Bacure](#bacure)
    - [Usage](#usage)
        - [Getting Started](#getting-started)
            - [IPv4](#ipv4)
            - [MSTP](#mstp)
        - [Configuration Details](#configuration-details)
            - [IPv4 Configuraion](#ipv4-configuraion)
            - [MSTP Configuration](#mstp-configuration)
        - [Multiple local devices](#multiple-local-devices)
        - [Data coercion](#data-coercion)
        - [Debugging Serial / MSTP](#debugging-serial--mstp)
    - [License](#license)

<!-- markdown-toc end -->

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

#### IPv4
The default `boot-up!` behavior uses IPv4. If you need to specify an interface
(for example if you have an Ethernet port AND a Wifi on your computer), use the
broadcast address associated with the interface.

```clj

(bacure.core/boot-up! {:broadcast-address "192.168.1.255"
                       :device-id 3333
                       :object-name "My awesome BACnet device"})

```

#### MSTP
For MSTP, we will typically want to be a Master node, and this is the default. Here's an example of how to do this on Linux:

```clj

(bacure.core/boot-up! {:network-type :mstp
                       :device-id    3333
                       :object-name  "My awesome BACnet device"
                       :com-port     "ttyS1"
                       :baud-rate    9600
                       :mstp-config  {:local-node-id      6
                                      :max-master-address 6}})

```

See [MSTP Configuration](#mstp-configuration) for more details.


### Configuration Details

  The optional config map can contain the following:

| Keyword                 | Type                        | Default              | Description                                                                                                                                                                                                                                                     |
|-------------------------|-----------------------------|----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| :network-type           | :ipv4 or :mstp              | :ipv4                | Specifies which type of network to create. Other config keywords may or may not be needed based on this value.                                                                                                                                                  |
| :device-id              | number                      | 1338                 | The device identifier on the network. It should be unique.                                                                                                                                                                                                      |
| :other-configs          | anything                    | nil                  | Any configuration returned when using the function 'get-configs'. These configuation can be set when the device is created simply by providing them in the arguments. For example, to change the vendor name, simply add '{:vendor-name \"some vendor name\"}'. |
| :model-name             | string                      | "Bacure"             |                                                                                                                                                                                                                                                                 |
| :object-name            | string                      | "Bacure <device id>" |                                                                                                                                                                                                                                                                 |
| :vendor-identifier      | number                      | 697                  |                                                                                                                                                                                                                                                                 |
| :apdu-timeout           | number                      | 6000                 | Time in milliseconds                                                                                                                                                                                                                                            |
| :number-of-apdu-retries | number                      | 2                    |                                                                                                                                                                                                                                                                 |
| :description            | string                      | see local_device.clj |                                                                                                                                                                                                                                                                 |
| :vendor-name            | string                      | "HVAC.IO"            |                                                                                                                                                                                                                                                                 |
| :foreign-device-target  | {:host string :port number} | nil                  | Will try to register as a foreign device to the provided host.                                                                                                                                                                                                  |




#### IPv4 Configuraion
The following optional keys are for IPv4 (:network-type = :ipv4)

| Keyword                         | Type              | Default              | Description                                                                                                                                                                                                                                                                                                                                                                              |
|---------------------------------|-------------------|----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| :broadcast-address              | string            | (generated)          | The address on which we send 'WhoIs'. You should not have to provide anything for this, unless you have multiple interfaces or want to trick your machine into sending to a 'fake' broadcast address.                                                                                                                                                                                    |
| :port                           | string            | 47808                | The BACnet port, usually 47808.                                                                                                                                                                                                                                                                                                                                                          |
| :local-address                  | string            | 0.0.0.0              | The default, \"0.0.0.0\", is also known as the 'anylocal'. (Default by the underlying BACnet4J library.) This is required on Linux, Solaris and some Windows machines in order to catch packet sent as a broadcast. You can manually change it, but unless you know exactly what you are doing, bad things will happen.                                                                  |

#### MSTP Configuration
The following keys are for MSTP (:network-type = :mstp)

| Keyword      | Type       | Default     | Description                                                                                                                                                                                                                                                                                                                                                                              |
|--------------|------------|-------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| :com-port    | string     | (REQUIRED)  | On Linux, this is usually /dev/ttyS[0-9] or /dev/ttyUSB[0-9]. For Windows, it's COM[0-9]*. You can use `(serial.util/list-ports)` to see what you have. (On Linux, make sure you have appropriate permissions, or you'll get "port in use" errors. I usually just change the permissions to 666, using  something like [this](https://bbs.archlinux.org/viewtopic.php?pid=676380#p676380)   |
| :baud-rate   | number     | 115200      | Supported speeds are 1200, 2400, 4800, 9600, 19200, 38400, 57600 and 115200 bits per second.                                                                                                                                                                                                                                                                                             |
| :databits    | DATABITS_* | DATABITS_8  | These live in serial.core                                                                                                                                                                                                                                                                                                                                                                |
| :stopbits    | STOPBITS_* | STOPBITS_1  | These live in serial.core                                                                                                                                                                                                                                                                                                                                                                |
| :parity      | PARITY_*   | PARITY_NONE | These live in serial.core                                                                                                                                                                                                                                                                                                                                                                |
| :mstp-config | map        | nil         | Additional MSTP configuration. See below.                                                                                                                                                                                                                                                                                                                                                |


The following keys can be included in the :mstp-config map mentioned above:

| Keyword          | Type  | Default           | Description |                                                                                                                                                                                                                                                                   |
|------------------|-------|-------------------|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| :node-type       | :mstp | :master or :slave |     :master | Only Master nodes can initiate requests and pass tokens. Slave nodes can only respond to requests, and are not part of the token-passing ring. [More information](http://store.chipkin.com/articles/how-does-bacnet-mstp-discover-new-devices-on-a-network) |
| :node-id         | :mstp | number            |           1 | Must be unique on the network. (The MAC address must also be unique, by the way!)                                                                                                                                                                                 |
| :debug-traffic   | :mstp | boolean           |       false | Turns on some very chatty logging in the REPL or console, which spits out all frames in and out. One of several debugging outlets, unfortunately.                                                                                                                 |
| :retry-count     | :mstp | number            |           3 | Relevant for Master nodes only. Specifies the number of transmission retries used for Token and Poll For Master transmissions.                                                                                                                                    |
| :max-info-frames | :mstp | number            |           8 | Specifies how many messages the controller can send out to other controllers when it has the token on the network.                                                                                                                                                |
| :max-master-id   | :mstp | number            |         127 | Highest node-id to search when doing Poll-for-Master. It's optimal to have your node-ids close to each other, and to set this value to no higher than your highest node-id.                                                                                       |

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


### Debugging Serial / MSTP

The MSTP network uses [clj-serial](https://github.com/peterschwarz/clj-serial),
which uses [PureJavaComm](https://github.com/nyholku/purejavacomm) for serial
communication. 

Unfortunately, BACnet4J and PureJavaComm use different methods of logging.
Here's a summary:

- To see BACnet4J log messages, see log/bacnet4j.log. Bacure's default
  configuration uses the WARN level of detail, but this can be configured using
  bacure/src/log4j.properties. To see pretty much all of the traffic, set the
  log level to TRACE.
- To see PureJavaComm's output dump, set `purejavacomm.loglevel` in the
  `:jvm-opts` in project.clj to something higher than 0 (and less than 7). This
  is also REPL/console output.

ProTip: Don't use Wireshark /
[mstpcap](https://github.com/stargieg/bacnet-stack/blob/master/demo/mstpcap/mstpcap.txt)
while running this on your PC. I thought the code was broken for the longest
time until I realized that mstpcap was somehow interfering with the frames.


## License

Copyright © 2019 Frozenlock

GNU General Public License version 3.0 (GPLv3)
