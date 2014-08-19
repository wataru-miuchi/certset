(ns certmon.core
  (:import [javax.net.ssl HttpsURLConnection SSLSocket SSLSocketFactory SSLSession SSLContext X509TrustManager TrustManager]
           [java.security SecureRandom]
           [java.security.cert X509Certificate]
           [javax.security.auth.x500 X500Principal]
           [javax.naming.ldap LdapName]))

(defn- get-cn [^String dn]
  (first (keep #(if (-> % .getType (.equalsIgnoreCase "CN")) (.getValue %)) (.getRdns (LdapName. dn)))))

(defn- format-certs [^X509Certificate cert]
  (assoc {}
    :cn (get-cn (.. cert getSubjectDN getName toString))
    :subject (.. cert getSubjectX500Principal toString)
    :issure  (.. cert getIssuerX500Principal toString)
    :expire  (.. cert getNotAfter toString)
    :since   (.. cert getNotBefore toString)))

;; fix for
;;   SunCertPathBuilderException:
;;     unable to find valid certification path to requested target
(def trust-manager
  (reify X509TrustManager
    (checkClientTrusted [_ _ _])
    (checkServerTrusted [_ _ _])
    (getAcceptedIssuers [_] nil)))

(defn
  ^{:doc "get server cert information from a specified host"}
  get-cert [^String hostname ^long port]

  (let [sc (doto (SSLContext/getInstance "SSL")
             (.init nil
                    (into-array TrustManager [trust-manager])
                    (SecureRandom.)))]
    (with-open [^SSLSocket socket (doto (. (.getSocketFactory sc) createSocket hostname port)
                                    (.startHandshake))]
      (mapv format-certs (.. socket (getSession) (getPeerCertificates)))
      )))
