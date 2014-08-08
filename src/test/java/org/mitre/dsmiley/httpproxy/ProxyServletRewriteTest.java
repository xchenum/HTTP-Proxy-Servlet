package org.mitre.dsmiley.httpproxy;

import com.meterware.httpunit.PutMethodWebRequest;
import com.meterware.httpunit.WebRequest;
import com.meterware.httpunit.WebResponse;
import com.meterware.servletunit.ServletRunner;
import com.meterware.httpunit.WebLink;
import com.meterware.servletunit.ServletUnitClient;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.localserver.LocalTestServer;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

/**
 * @author Xu Chen - xchenum@gmail.com
 */
public class ProxyServletRewriteTest {
    private static final Log log = LogFactory.getLog(ProxyServletRewriteTest.class);

    /**
     * From Apache httpcomponents/httpclient. Note httpunit has a similar thing called PseudoServlet but it is
     * not as good since you can't even make it echo the request back.
     */
    protected LocalTestServer localTestServer;

    /**
     * From Meterware httpunit.
     */
    protected ServletRunner servletRunner;
    private ServletUnitClient sc;

    protected String targetBaseUri;
    protected String sourceBaseUri;

    @Before
    public void setUp() throws Exception {
        localTestServer = new LocalTestServer(null, null);
        localTestServer.start();
        localTestServer.register("/*", new RequestInfoHandler());

        servletRunner = new ServletRunner();

        Properties servletProps = new Properties();
        servletProps.setProperty("http.protocol.handle-redirects", "false");
        servletProps.setProperty(ProxyServlet.P_LOG, "true");
        servletProps.setProperty(ProxyServlet.P_FORWARDEDFOR, "true");
        servletProps.setProperty(ProxyServlet.P_ENABLEREWRITE, "true");
        setUpServlet(servletProps);

        sc = servletRunner.newClient();
        sc.getClientProperties().setAutoRedirect(false);//don't want httpunit itself to redirect

        this.linkMap = new HashMap<String, String>();
        linkMap.put(
                "<a href=\"/stages\">Stages</a>",
                "<a href=\"/proxyMe/stages\">Stages</a>");
        linkMap.put(
                "<a href=\"/stages/\">Stages</a>",
                "<a href=\"/proxyMe/stages/\">Stages</a>");
        linkMap.put(
                "<a href=\"" + targetBaseUri + "/stages/\">Stages</a>",
                "<a href=\"/proxyMe/stages/\">Stages</a>");
        linkMap.put(
                "<link rel=\"stylesheet\" href=\"" + targetBaseUri + "/static/webui.css\" type=\"text/css\"/>",
                "<link rel=\"stylesheet\" href=\"/proxyMe/static/webui.css\" type=\"text/css\" />");
        linkMap.put(
                "<a href=\"http://somewhereontheinternet.com\">some</a>",
                "<a href=\"http://somewhereontheinternet.com\">some</a>");
    }

    protected void setUpServlet(Properties servletProps) {
        servletProps.putAll(servletProps);
        targetBaseUri = "http://localhost:" + localTestServer.getServiceAddress().getPort();
        servletProps.setProperty("targetUri", targetBaseUri);
        servletRunner.registerServlet("/proxyMe/*", ProxyServlet.class.getName(), servletProps);//also matches /proxyMe (no path info)
        sourceBaseUri = "http://localhost/proxyMe";//localhost:0 is hard-coded in ServletUnitHttpRequest
    }

    @After
    public void tearDown() throws Exception {
        servletRunner.shutDown();
        localTestServer.stop();
    }

    //note: we don't include fragments:   "/p?#f","/p?#" because
    //  user agents aren't supposed to send them. HttpComponents has behaved
    //  differently on sending them vs not sending them.
    private static String[] testUrlSuffixes = new String[]{
            "", "/pathInfo/", "?q=v", "/p?q=v",
            "/p?query=note:Leitbild",//colon  Issue#4
            "/p?id=p%20i", "/p%20i" // encoded space in param then in path
    };

    private static Map<String, String> linkMap;

    @Test
    public void testGetRewrite() throws Exception {
        for (String urlSuffix : testUrlSuffixes) {
            for (String link : linkMap.keySet()) {
                execAndAssertBody(
                        makePutRequest(
                                sourceBaseUri + urlSuffix,
                                link),
                        linkMap.get(link));
            }
        }
    }

    private WebResponse execAndAssertBody(
            WebRequest req,
            String expectedLink) throws Exception {
        WebResponse rsp = sc.getResponse(req);
        assertTrue(rsp.getText().contains(expectedLink));
        return rsp;
    }

    private PutMethodWebRequest makePutRequest(
            String incomingUrl, final String enclosedString) {
        log.info("Making request to url " + incomingUrl);
        final String url = rewriteMakeMethodUrl(incomingUrl);
        InputStream stream = new ByteArrayInputStream(enclosedString.getBytes(StandardCharsets.UTF_8));

        PutMethodWebRequest req = new PutMethodWebRequest(
                url,
                stream,
                "text/plain");
        return req;
    }

    //subclass extended
    protected String rewriteMakeMethodUrl(String url) {
        return url;
    }


    /**
     * Writes all information about the request back to the response.
     */
    private static class RequestInfoHandler implements HttpRequestHandler {

        public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
            response.setStatusCode(200);
            response.setReasonPhrase("TESTREASON");

            if (request instanceof HttpEntityEnclosingRequest) {
                HttpEntityEnclosingRequest enclosingRequest = (HttpEntityEnclosingRequest) request;
                HttpEntity entity = enclosingRequest.getEntity();
                response.setEntity(new StringEntity(EntityUtils.toString(entity), ContentType.TEXT_HTML));
            } else {
                response.setEntity(new StringEntity("request body empty", ContentType.TEXT_HTML));
            }
        }
    }
}
