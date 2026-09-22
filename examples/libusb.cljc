(ns libusb)

(require '[babashka.ffi :as ffi :refer [defcfn]])

(ffi/load-library {:mac ["libusb-1.0.dylib" "libusb-1.0.0.dylib"]
                   :linux ["libusb-1.0.so.0" "libusb-1.0.so"]
                   :windows "libusb-1.0.dll"})

(defcfn libusb-init "libusb_init" [:pointer] :int)
(defcfn libusb-exit "libusb_exit" [:pointer] :void)
(defcfn libusb-get-device-list "libusb_get_device_list" [:pointer :pointer] :ssize_t)
(defcfn libusb-free-device-list "libusb_free_device_list" [:pointer :int] :void)
(defcfn libusb-get-device-descriptor "libusb_get_device_descriptor" [:pointer :pointer] :int)
(defcfn libusb-get-bus-number "libusb_get_bus_number" [:pointer] :uint8)
(defcfn libusb-get-device-address "libusb_get_device_address" [:pointer] :uint8)
(defcfn libusb-open "libusb_open" [:pointer :pointer] :int)
(defcfn libusb-close "libusb_close" [:pointer] :void)
(defcfn libusb-get-string-descriptor-ascii
  "libusb_get_string_descriptor_ascii" [:pointer :uint8 :pointer :int] :int)
(defcfn libusb-error-name "libusb_error_name" [:int] :string)

(def device-descriptor
  [:struct [[:bLength :uint8]
            [:bDescriptorType :uint8]
            [:bcdUSB :uint16]
            [:bDeviceClass :uint8]
            [:bDeviceSubClass :uint8]
            [:bDeviceProtocol :uint8]
            [:bMaxPacketSize0 :uint8]
            [:idVendor :uint16]
            [:idProduct :uint16]
            [:bcdDevice :uint16]
            [:iManufacturer :uint8]
            [:iProduct :uint8]
            [:iSerialNumber :uint8]
            [:bNumConfigurations :uint8]]])

(defn check [rc]
  (when (neg? rc)
    (throw (ex-info (libusb-error-name rc) {:rc rc})))
  rc)

(defn pad [s width]
  (let [s (str s)]
    (str (apply str (repeat (- width (count s)) "0")) s)))

(defn hex4 [n]
  (pad #?(:clj (Long/toHexString n) :cljs (.toString n 16)) 4))

(defn string-descriptor [arena dev idx]
  (when (pos? idx)
    (let [ph (ffi/alloc arena :pointer)]
      (when (zero? (libusb-open dev ph))
        (let [handle (ffi/read ph :pointer)
              buf (ffi/alloc arena 256)
              n (libusb-get-string-descriptor-ascii handle idx buf 256)]
          (libusb-close handle)
          (when (pos? n)
            (ffi/ptr->string buf)))))))

(#?(:clj with-open :cljs ffi/with-open) [arena (ffi/confined-arena)]
  (let [pctx (ffi/alloc arena :pointer)
        _ (check (libusb-init pctx))
        ctx (ffi/read pctx :pointer)
        plist (ffi/alloc arena :pointer)
        n (check (libusb-get-device-list ctx plist))
        list (ffi/reinterpret (ffi/read plist :pointer) (* n (ffi/sizeof :pointer)))]
    (try
      (doseq [i (range n)]
        (let [dev (ffi/read list :pointer (* i (ffi/sizeof :pointer)))
              pdesc (ffi/alloc arena device-descriptor)
              _ (check (libusb-get-device-descriptor dev pdesc))
              desc (ffi/read pdesc device-descriptor)]
          (println (str "Bus " (pad (libusb-get-bus-number dev) 3)
                        " Device " (pad (libusb-get-device-address dev) 3)
                        ": ID " (hex4 (:idVendor desc)) ":" (hex4 (:idProduct desc))
                        " " (or (string-descriptor arena dev (:iManufacturer desc)) "")
                        " " (or (string-descriptor arena dev (:iProduct desc)) "")))))
      (finally
        (libusb-free-device-list (ffi/read plist :pointer) 1)
        (libusb-exit ctx)))))
