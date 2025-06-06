/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.Principal;
import java.security.PrivilegedExceptionAction;
import java.util.Set;
import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosTicket;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.HBaseCommonTestingUtil;
import org.apache.hadoop.hbase.http.TestHttpServer.EchoServlet;
import org.apache.hadoop.hbase.http.resource.JerseyResource;
import org.apache.hadoop.hbase.testclassification.MiscTests;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.apache.hadoop.hbase.util.SimpleKdcServerUtil;
import org.apache.hadoop.security.authentication.util.KerberosName;
import org.apache.hadoop.security.authorize.AccessControlList;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.KerberosCredentials;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Lookup;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.impl.auth.SPNegoSchemeFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.client.JaasKrbUtil;
import org.apache.kerby.kerberos.kerb.server.SimpleKdcServer;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test class for SPNEGO Proxyuser authentication on the HttpServer. Uses Kerby's MiniKDC and Apache
 * HttpComponents to verify that the doas= mechanicsm works, and that the proxyuser settings are
 * observed.
 */
@Category({ MiscTests.class, SmallTests.class })
public class TestProxyUserSpnegoHttpServer extends HttpServerFunctionalTest {
  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestProxyUserSpnegoHttpServer.class);

  private static final Logger LOG = LoggerFactory.getLogger(TestProxyUserSpnegoHttpServer.class);
  private static final String KDC_SERVER_HOST = "localhost";
  private static final String WHEEL_PRINCIPAL = "wheel";
  private static final String UNPRIVILEGED_PRINCIPAL = "unprivileged";
  private static final String PRIVILEGED_PRINCIPAL = "privileged";
  private static final String PRIVILEGED2_PRINCIPAL = "privileged2";

  private static HttpServer server;
  private static URL baseUrl;
  private static SimpleKdcServer kdc;
  private static File infoServerKeytab;
  private static File wheelKeytab;
  private static File unprivilegedKeytab;
  private static File privilegedKeytab;
  private static File privileged2Keytab;

  @BeforeClass
  public static void setupServer() throws Exception {
    Configuration conf = new Configuration();
    HBaseCommonTestingUtil htu = new HBaseCommonTestingUtil(conf);

    final String serverPrincipal = "HTTP/" + KDC_SERVER_HOST;

    kdc = SimpleKdcServerUtil.getRunningSimpleKdcServer(new File(htu.getDataTestDir().toString()),
      HBaseCommonTestingUtil::randomFreePort);
    File keytabDir = new File(htu.getDataTestDir("keytabs").toString());
    if (keytabDir.exists()) {
      deleteRecursively(keytabDir);
    }
    keytabDir.mkdirs();

    infoServerKeytab = new File(keytabDir, serverPrincipal.replace('/', '_') + ".keytab");
    wheelKeytab = new File(keytabDir, WHEEL_PRINCIPAL + ".keytab");
    unprivilegedKeytab = new File(keytabDir, UNPRIVILEGED_PRINCIPAL + ".keytab");
    privilegedKeytab = new File(keytabDir, PRIVILEGED_PRINCIPAL + ".keytab");
    privileged2Keytab = new File(keytabDir, PRIVILEGED2_PRINCIPAL + ".keytab");

    setupUser(kdc, wheelKeytab, WHEEL_PRINCIPAL);
    setupUser(kdc, unprivilegedKeytab, UNPRIVILEGED_PRINCIPAL);
    setupUser(kdc, privilegedKeytab, PRIVILEGED_PRINCIPAL);
    setupUser(kdc, privileged2Keytab, PRIVILEGED2_PRINCIPAL);

    setupUser(kdc, infoServerKeytab, serverPrincipal);

    buildSpnegoConfiguration(conf, serverPrincipal, infoServerKeytab);
    AccessControlList acl = InfoServer.buildAdminAcl(conf);

    server = createTestServerWithSecurityAndAcl(conf, acl);
    server.addPrivilegedServlet("echo", "/echo", EchoServlet.class);
    server.addJerseyResourcePackage(JerseyResource.class.getPackage().getName(), "/jersey/*");
    server.start();
    baseUrl = getServerURL(server);

    LOG.info("HTTP server started: " + baseUrl);
  }

  @AfterClass
  public static void stopServer() throws Exception {
    try {
      if (null != server) {
        server.stop();
      }
    } catch (Exception e) {
      LOG.info("Failed to stop info server", e);
    }
    try {
      if (null != kdc) {
        kdc.stop();
      }
    } catch (Exception e) {
      LOG.info("Failed to stop mini KDC", e);
    }
  }

  private static void setupUser(SimpleKdcServer kdc, File keytab, String principal)
    throws KrbException {
    kdc.createPrincipal(principal);
    kdc.exportPrincipal(principal, keytab);
  }

  protected static Configuration buildSpnegoConfiguration(Configuration conf,
    String serverPrincipal, File serverKeytab) {
    KerberosName.setRules("DEFAULT");

    conf.setInt(HttpServer.HTTP_MAX_THREADS, TestHttpServer.MAX_THREADS);

    // Enable Kerberos (pre-req)
    conf.set("hbase.security.authentication", "kerberos");
    conf.set(HttpServer.HTTP_UI_AUTHENTICATION, "kerberos");
    conf.set(HttpServer.HTTP_SPNEGO_AUTHENTICATION_PRINCIPAL_KEY, serverPrincipal);
    conf.set(HttpServer.HTTP_SPNEGO_AUTHENTICATION_KEYTAB_KEY, serverKeytab.getAbsolutePath());

    conf.set(HttpServer.HTTP_SPNEGO_AUTHENTICATION_ADMIN_USERS_KEY, PRIVILEGED_PRINCIPAL);
    conf.set(HttpServer.HTTP_SPNEGO_AUTHENTICATION_PROXYUSER_ENABLE_KEY, "true");
    conf.set("hadoop.security.authorization", "true");

    conf.set("hadoop.proxyuser.wheel.hosts", "*");
    conf.set("hadoop.proxyuser.wheel.users", PRIVILEGED_PRINCIPAL + "," + UNPRIVILEGED_PRINCIPAL);
    return conf;
  }

  @Test
  public void testProxyAllowed() throws Exception {
    testProxy(WHEEL_PRINCIPAL, PRIVILEGED_PRINCIPAL, HttpURLConnection.HTTP_OK, null);
  }

  @Test
  public void testProxyDisallowedForUnprivileged() throws Exception {
    testProxy(WHEEL_PRINCIPAL, UNPRIVILEGED_PRINCIPAL, HttpURLConnection.HTTP_FORBIDDEN,
      "403 User unprivileged is unauthorized to access this page.");
  }

  @Test
  public void testProxyDisallowedForNotSudoAble() throws Exception {
    testProxy(WHEEL_PRINCIPAL, PRIVILEGED2_PRINCIPAL, HttpURLConnection.HTTP_FORBIDDEN,
      "403 Forbidden");
  }

  public void testProxy(String clientPrincipal, String doAs, int responseCode, String statusLine)
    throws Exception {
    // Create the subject for the client
    final Subject clientSubject = JaasKrbUtil.loginUsingKeytab(WHEEL_PRINCIPAL, wheelKeytab);
    final Set<Principal> clientPrincipals = clientSubject.getPrincipals();
    // Make sure the subject has a principal
    assertFalse(clientPrincipals.isEmpty());

    // Get a TGT for the subject (might have many, different encryption types). The first should
    // be the default encryption type.
    Set<KerberosTicket> privateCredentials =
      clientSubject.getPrivateCredentials(KerberosTicket.class);
    assertFalse(privateCredentials.isEmpty());
    KerberosTicket tgt = privateCredentials.iterator().next();
    assertNotNull(tgt);

    // The name of the principal
    final String principalName = clientPrincipals.iterator().next().getName();

    // Run this code, logged in as the subject (the client)
    HttpResponse resp = Subject.doAs(clientSubject, new PrivilegedExceptionAction<HttpResponse>() {
      @Override
      public HttpResponse run() throws Exception {
        // Logs in with Kerberos via GSS
        GSSManager gssManager = GSSManager.getInstance();
        // jGSS Kerberos login constant
        Oid oid = new Oid("1.2.840.113554.1.2.2");
        GSSName gssClient = gssManager.createName(principalName, GSSName.NT_USER_NAME);
        GSSCredential credential = gssManager.createCredential(gssClient,
          GSSCredential.DEFAULT_LIFETIME, oid, GSSCredential.INITIATE_ONLY);

        HttpClientContext context = HttpClientContext.create();
        Lookup<AuthSchemeProvider> authRegistry = RegistryBuilder.<AuthSchemeProvider> create()
          .register(AuthSchemes.SPNEGO, new SPNegoSchemeFactory(true, true)).build();

        HttpClient client = HttpClients.custom().setDefaultAuthSchemeRegistry(authRegistry).build();
        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new KerberosCredentials(credential));

        URL url = new URL(getServerURL(server), "/echo?doAs=" + doAs + "&a=b");
        context.setTargetHost(new HttpHost(url.getHost(), url.getPort()));
        context.setCredentialsProvider(credentialsProvider);
        context.setAuthSchemeRegistry(authRegistry);

        HttpGet get = new HttpGet(url.toURI());
        return client.execute(get, context);
      }
    });

    assertNotNull(resp);
    assertEquals(responseCode, resp.getStatusLine().getStatusCode());
    if (responseCode == HttpURLConnection.HTTP_OK) {
      assertTrue(EntityUtils.toString(resp.getEntity()).trim().contains("a:b"));
    } else {
      assertTrue(resp.getStatusLine().toString().contains(statusLine)
        || EntityUtils.toString(resp.getEntity()).contains(statusLine));
    }
  }

}
