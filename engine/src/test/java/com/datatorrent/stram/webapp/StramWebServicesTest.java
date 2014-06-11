/**
 * Copyright (c) 2012-2013 DataTorrent, Inc.
 * All rights reserved.
 */
package com.datatorrent.stram.webapp;

import com.datatorrent.stram.StramAppContext;
import com.datatorrent.stram.StreamingContainerManager;
import com.datatorrent.stram.api.BaseContext;
import com.datatorrent.stram.plan.logical.LogicalPlan;
import com.datatorrent.stram.plan.logical.requests.CreateOperatorRequest;
import com.datatorrent.stram.plan.logical.requests.LogicalPlanRequest;
import com.datatorrent.stram.plan.logical.requests.SetOperatorPropertyRequest;
import com.datatorrent.stram.support.StramTestSupport;
import com.datatorrent.stram.webapp.StramWebApp.JAXBContextResolver;
import com.datatorrent.stram.webapp.StramWebServicesTest.GuiceServletConfig.DummyStreamingContainerManager;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.servlet.ServletModule;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.ClientResponse.Status;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.util.Clock;
import org.apache.hadoop.yarn.webapp.GenericExceptionHandler;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.ws.rs.core.MediaType;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;

import static org.junit.Assert.*;

/**
 * Test the application master web services api's.
 * Also test non-existent urls.
 * Adapted from MR.
 *
 *  /ws/v1/stram
 *  /ws/v1/stram/info
 */
public class StramWebServicesTest extends JerseyTest
{
  private static Configuration conf = new Configuration();
  private static TestAppContext appContext;

  static class TestAppContext extends BaseContext implements StramAppContext
  {
    private static final long serialVersionUID = 201309121323L;
    final ApplicationAttemptId appAttemptID;
    final ApplicationId appID;
    final String appPath = "/testPath";
    final String userId = "testUser";
    final long startTime = System.currentTimeMillis();
    final String gatewayAddress = "localhost:9090";

    TestAppContext(int appid, int numJobs, int numTasks, int numAttempts)
    {
      super(null, null); // this needs to be done in a proper way - may cause application errors.
      this.appID = ApplicationId.newInstance(0, appid);
      this.appAttemptID = ApplicationAttemptId.newInstance(this.appID, numAttempts);
    }

    TestAppContext()
    {
      this(0, 1, 1, 1);
    }

    @Override
    public ApplicationAttemptId getApplicationAttemptId()
    {
      return appAttemptID;
    }

    @Override
    public ApplicationId getApplicationID()
    {
      return appID;
    }

    @Override
    public String getApplicationPath()
    {
      return appPath;
    }

    @Override
    public String getAppMasterTrackingUrl()
    {
      return "unknown";
    }

    @Override
    public CharSequence getUser()
    {
      return userId;
    }

    @Override
    public Clock getClock()
    {
      return null;
    }

    @Override
    public String getApplicationName()
    {
      return "TestApp";
    }

    @Override
    public String getApplicationDocLink()
    {
      return "TestAppDocLink";
    }

    @Override
    public long getStartTime()
    {
      return startTime;
    }

    @Override
    public AppInfo.AppStats getStats()
    {
      return new AppInfo.AppStats()
      {
      };
    }

    @Override
    public String getGatewayAddress()
    {
      return gatewayAddress;
    }

    @Override
    public String getLicenseId()
    {
      return null;
    }

    @Override
    public long getRemainingLicensedMB()
    {
      return 0;
    }

    @Override
    public long getTotalLicensedMB() {
      return 0;
    }

    @Override
    public long getAllocatedMB()
    {
      return 0;
    }

    @Override
    public long getLicenseInfoLastUpdate()
    {
      return 0;
    }

  }

  public static class SomeStats
  {
    public int field1 = 2;
  }

  public static class GuiceServletConfig extends com.google.inject.servlet.GuiceServletContextListener
  {
    // new instance needs to be created for each test
    public static class DummyStreamingContainerManager extends StreamingContainerManager
    {
      public static List<LogicalPlanRequest> lastRequests;

      DummyStreamingContainerManager(LogicalPlan dag)
      {
        super(dag);
      }

      @Override
      public FutureTask<Object> logicalPlanModification(final List<LogicalPlanRequest> requests) throws Exception
      {
        lastRequests = requests;

        // delegate processing to dispatch thread
        FutureTask<Object> future = new FutureTask<Object>(new Callable<Object>()
        {
          @Override
          public Object call() throws Exception
          {
            return requests;
          }

        });
        future.run();
        //LOG.info("Scheduled plan changes: {}", requests);
        return future;
      }

    }

    private final Injector injector = Guice.createInjector(new ServletModule()
    {
      @Override
      protected void configureServlets()
      {
        LogicalPlan dag = new LogicalPlan();
        dag.setAttribute(LogicalPlan.APPLICATION_PATH, StramWebServicesTest.class.getName());
        final DummyStreamingContainerManager streamingContainerManager = new DummyStreamingContainerManager(dag);

        appContext = new TestAppContext();
        bind(JAXBContextResolver.class);
        bind(StramWebServices.class);
        bind(GenericExceptionHandler.class);
        bind(StramAppContext.class).toInstance(appContext);
        bind(StreamingContainerManager.class).toInstance(streamingContainerManager);
        bind(Configuration.class).toInstance(conf);
        serve("/*").with(GuiceContainer.class);
      }

    });

    @Override
    protected Injector getInjector()
    {
      return injector;
    }

  }

  @Before
  @Override
  public void setUp() throws Exception
  {
    super.setUp();
  }

  public StramWebServicesTest()
  {
    super(new WebAppDescriptor.Builder(
            StramWebServicesTest.class.getPackage().getName())
            .contextListenerClass(GuiceServletConfig.class)
            .filterClass(com.google.inject.servlet.GuiceFilter.class)
            .contextPath("jersey-guice-filter").servletPath("/").build());
  }

  @Test
  public void testAM() throws JSONException, Exception
  {
    WebResource r = resource();
    ClientResponse response = r.path(StramWebServices.PATH)
            .accept(MediaType.APPLICATION_JSON).get(ClientResponse.class);
    assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
    JSONObject json = response.getEntity(JSONObject.class);
    assertTrue("Too few elements", json.length() > 1);
    verifyAMInfo(json, appContext);
  }

  @Test
  public void testAMSlash() throws JSONException, Exception
  {
    WebResource r = resource();
    ClientResponse response = r.path(StramWebServices.PATH + "/")
            .accept(MediaType.APPLICATION_JSON).get(ClientResponse.class);
    assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
    JSONObject json = response.getEntity(JSONObject.class);
    assertTrue("Too few elements", json.length() > 1);
    verifyAMInfo(json, appContext);
  }

  @Test
  public void testAMDefault() throws JSONException, Exception
  {
    WebResource r = resource();
    ClientResponse response = r.path(StramWebServices.PATH + "/")
            .get(ClientResponse.class);
    assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
    JSONObject json = response.getEntity(JSONObject.class);
    assertTrue("Too few elements", json.length() > 1);
    verifyAMInfo(json, appContext);
  }

  @Ignore
  @Test
  public void testAMXML() throws JSONException, Exception
  {
    WebResource r = resource();
    ClientResponse response = r.path(StramWebServices.PATH)
            .accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
    assertEquals(MediaType.APPLICATION_XML_TYPE, response.getType());
    String xml = response.getEntity(String.class);
    verifyAMInfoXML(xml, appContext);
  }

  @Test
  public void testInfo() throws JSONException, Exception
  {
    WebResource r = resource();
    ClientResponse response = r.path(StramWebServices.PATH)
            .path(StramWebServices.PATH_INFO).accept(MediaType.APPLICATION_JSON)
            .get(ClientResponse.class);
    assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
    JSONObject json = response.getEntity(JSONObject.class);
    assertTrue("Too few elements", json.length() > 1);
    verifyAMInfo(json, appContext);
  }

  @Test
  public void testInfoSlash() throws JSONException, Exception
  {
    WebResource r = resource();
    ClientResponse response = r.path(StramWebServices.PATH)
            .path(StramWebServices.PATH_INFO + "/").accept(MediaType.APPLICATION_JSON)
            .get(ClientResponse.class);
    assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
    JSONObject json = response.getEntity(JSONObject.class);
    assertTrue("Too few elements", json.length() > 1);
    verifyAMInfo(json, appContext);
  }

  @Test
  public void testInfoDefault() throws JSONException, Exception
  {
    WebResource r = resource();
    ClientResponse response = r.path(StramWebServices.PATH)
            .path(StramWebServices.PATH_INFO + "/").get(ClientResponse.class);
    assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
    JSONObject json = response.getEntity(JSONObject.class);
    assertTrue("Too few elements", json.length() > 1);
    verifyAMInfo(json, appContext);
  }

  @Ignore
  @Test
  public void testInfoXML() throws JSONException, Exception
  {
    WebResource r = resource();
    ClientResponse response = r.path(StramWebServices.PATH)
            .path(StramWebServices.PATH_INFO + "/").accept(MediaType.APPLICATION_XML)
            .get(ClientResponse.class);
    assertEquals(MediaType.APPLICATION_XML_TYPE, response.getType());
    String xml = response.getEntity(String.class);
    verifyAMInfoXML(xml, appContext);
  }

  @Test
  @SuppressWarnings("UnusedAssignment")
  public void testInvalidUri() throws JSONException, Exception
  {
    WebResource r = resource();
    String responseStr = "";
    try {
      responseStr = r.path(StramWebServices.PATH).path("bogus")
              .accept(MediaType.APPLICATION_JSON).get(String.class);
      fail("should have thrown exception on invalid uri");
    }
    catch (UniformInterfaceException ue) {
      ClientResponse response = ue.getResponse();
      assertEquals(Status.NOT_FOUND, response.getClientResponseStatus());
      StramTestSupport.checkStringMatch(
              "error string exists and shouldn't", "", responseStr);
    }
  }

  @Test
  @SuppressWarnings("UnusedAssignment")
  public void testInvalidUri2() throws JSONException, Exception
  {
    WebResource r = resource();
    String responseStr = "";
    try {
      responseStr = r.path("ws").path("v1").path("invalid")
              .accept(MediaType.APPLICATION_JSON).get(String.class);
      fail("should have thrown exception on invalid uri");
    }
    catch (UniformInterfaceException ue) {
      ClientResponse response = ue.getResponse();
      assertEquals(Status.NOT_FOUND, response.getClientResponseStatus());
      StramTestSupport.checkStringMatch(
              "error string exists and shouldn't", "", responseStr);
    }
  }

  @Test
  @SuppressWarnings("UnusedAssignment")
  public void testInvalidAccept() throws JSONException, Exception
  {
    // suppress logging in jersey to get rid of expected stack traces from test log
    java.util.logging.Logger.getLogger("org.glassfish.grizzly.servlet.ServletHandler").setLevel(Level.OFF);
    java.util.logging.Logger.getLogger("com.sun.jersey.spi.container.ContainerResponse").setLevel(Level.OFF);

    WebResource r = resource();
    String responseStr = "";
    try {
      responseStr = r.path(StramWebServices.PATH)
              .accept(MediaType.TEXT_PLAIN).get(String.class);
      fail("should have thrown exception on invalid accept");
    }
    catch (UniformInterfaceException ue) {
      ClientResponse response = ue.getResponse();
      assertEquals(Status.INTERNAL_SERVER_ERROR,
                   response.getClientResponseStatus());
      StramTestSupport.checkStringMatch(
              "error string exists and shouldn't", "", responseStr);
    }
  }

  @Test
  public void testSubmitLogicalPlanChange() throws JSONException, Exception
  {
    List<LogicalPlanRequest> requests = new ArrayList<LogicalPlanRequest>();
    WebResource r = resource();

    CreateOperatorRequest request1 = new CreateOperatorRequest();
    request1.setOperatorName("operatorName");
    request1.setOperatorFQCN("className");
    requests.add(request1);

    SetOperatorPropertyRequest request2 = new SetOperatorPropertyRequest();
    request2.setOperatorName("operatorName");
    request2.setPropertyName("propertyName");
    request2.setPropertyValue("propertyValue");
    requests.add(request2);

    ObjectMapper mapper = new ObjectMapper();
    final Map<String, Object> m = new HashMap<String, Object>();
    m.put("requests", requests);
    final JSONObject jsonRequest = new JSONObject(mapper.writeValueAsString(m));

    ClientResponse response = r.path(StramWebServices.PATH)
        .path(StramWebServices.PATH_LOGICAL_PLAN).accept(MediaType.APPLICATION_JSON)
        .post(ClientResponse.class, jsonRequest);
    assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
    assertEquals(DummyStreamingContainerManager.lastRequests.size(), 2);
    LogicalPlanRequest request = DummyStreamingContainerManager.lastRequests.get(0);
    assertTrue(request instanceof CreateOperatorRequest);
    request1 = (CreateOperatorRequest)request;
    assertEquals(request1.getOperatorName(), "operatorName");
    assertEquals(request1.getOperatorFQCN(), "className");
    request = DummyStreamingContainerManager.lastRequests.get(1);
    assertTrue(request instanceof SetOperatorPropertyRequest);
    request2 = (SetOperatorPropertyRequest)request;
    assertEquals(request2.getOperatorName(), "operatorName");
    assertEquals(request2.getPropertyName(), "propertyName");
    assertEquals(request2.getPropertyValue(), "propertyValue");
  }

  void verifyAMInfo(JSONObject info, TestAppContext ctx)
          throws JSONException
  {
    assertTrue("Too few elements", info.length() > 10);

    verifyAMInfoGeneric(ctx, info.getString("id"), info.getString("user"),
        info.getString("name"), info.getLong("startTime"),
        info.getLong("elapsedTime"));
  }

  void verifyAMInfoXML(String xml, TestAppContext ctx)
          throws JSONException, Exception
  {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    DocumentBuilder db = dbf.newDocumentBuilder();
    InputSource is = new InputSource();
    is.setCharacterStream(new StringReader(xml));
    Document dom = db.parse(is);
    NodeList nodes = dom.getElementsByTagName(StramWebServices.PATH_INFO);
    assertEquals("incorrect number of elements", 1, nodes.getLength());

    for (int i = 0; i < nodes.getLength(); i++) {
      Element element = (Element)nodes.item(i);
      verifyAMInfoGeneric(ctx,
          getXmlString(element, "id"),
          getXmlString(element, "user"),
          getXmlString(element, "name"),
          getXmlLong(element, "startTime"),
          getXmlLong(element, "elapsedTime"));
    }
  }

  void verifyAMInfoGeneric(TestAppContext ctx, String id, String user,
      String name, long startTime, long elapsedTime) {

    StramTestSupport.checkStringMatch("id", ctx.getApplicationID()
            .toString(), id);
    StramTestSupport.checkStringMatch("user", ctx.getUser().toString(),
                                      user);
    StramTestSupport.checkStringMatch("name", ctx.getApplicationName(),
                                      name);

    assertEquals("startTime incorrect", ctx.getStartTime(), startTime);
    assertTrue("elapsedTime not greater then 0", (elapsedTime > 0));

  }

  public static String getXmlString(Element element, String name)
  {
    NodeList id = element.getElementsByTagName(name);
    Element line = (Element)id.item(0);
    if (line == null) {
      return null;
    }
    Node first = line.getFirstChild();
    // handle empty <key></key>
    if (first == null) {
      return "";
    }
    String val = first.getNodeValue();
    if (val == null) {
      return "";
    }
    return val;
  }

  public static long getXmlLong(Element element, String name)
  {
    String val = getXmlString(element, name);
    return Long.parseLong(val);
  }

}
