package Server; /**
 * Created by niklas on 03.01.17.
 */

import Server.functions.LabelFilter;
import Server.functions.LabelGroupReducer;
import Server.functions.LabelMapper;
import Server.functions.LabelReducer;
import Server.functions.PropertyKeyMapper;
import Server.functions.MergeToSet;
import Server.pojo.GroupingRequest;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.io.LocalCollectionOutputFormat;
import org.apache.flink.api.java.tuple.Tuple3;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.gradoop.common.model.impl.id.GradoopId;
import org.gradoop.common.model.impl.pojo.Edge;
import org.gradoop.common.model.impl.pojo.GraphHead;
import org.gradoop.common.model.impl.pojo.Vertex;
import org.gradoop.flink.io.impl.json.JSONDataSource;
import org.gradoop.flink.model.impl.GraphCollection;
import org.gradoop.flink.model.impl.LogicalGraph;
import org.gradoop.flink.model.impl.operators.grouping.Grouping;
import org.gradoop.flink.model.impl.operators.grouping.GroupingStrategy;
import org.gradoop.flink.model.impl.operators.grouping.functions.aggregation.CountAggregator;
import org.gradoop.flink.model.impl.operators.grouping.functions.aggregation.MaxAggregator;
import org.gradoop.flink.model.impl.operators.grouping.functions.aggregation.MinAggregator;
import org.gradoop.flink.model.impl.operators.grouping.functions.aggregation.SumAggregator;
import org.gradoop.flink.util.GradoopFlinkConfig;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;


/**
 * Handles REST requests to the server.
 */
@Path("")
public class RequestHandler {


  List<String> bufferedVertexLabels;
  List<String> bufferedEdgeLabels;

  private static final ExecutionEnvironment ENV = ExecutionEnvironment.createLocalEnvironment();
  private GradoopFlinkConfig config = GradoopFlinkConfig.createConfig(ENV);

  /**
   * Creates a list of all available databases from the file structure under the /data/ folder.
   * @return List of folders (datbases) under the /data/ folder.
   */
  @GET
  @Path("/databases")
  @Produces("application/json;charset=utf-8")
  public Response getDatabases() {

    JSONArray jsonArray = new JSONArray();

    // get all subfolders of "/data/", they are considered as databases
    File dataFolder = new File(RequestHandler.class.getResource("/data/").getFile());
    String[] databases = dataFolder.list(new FilenameFilter() {
      public boolean accept(File current, String name) {
        return new File(current, name).isDirectory();
      }
    });

    // return the found databases to the client
    assert databases != null;
    for(String database : databases) {
      jsonArray.put(database);
    }
    return Response.ok(jsonArray.toString()).build();
  }

  /**
   * Takes a database name via a POST request and returns the keys of all
   * vertex and edge properties, and a boolean value specifying if the property has a numerical
   * type. The return is a string in the JSON format, for easy usage in a JavaScript web page.
   * @param databaseName name of the loaded database
   * @return  A JSON containing the vertices and edges property keys
   */
  @POST
  @Path("/keys/{databaseName}")
  @Produces("application/json;charset=utf-8")
  public Response getKeysAndLabels(@PathParam("databaseName") String databaseName) {

    // load the database
    String graphPath =
      Server.class.getResource("/data/" + databaseName + "/graphs.json").getPath();
    String vertexPath =
      Server.class.getResource("/data/" + databaseName + "/vertices.json").getPath();
    String edgePath =
      Server.class.getResource("/data/" + databaseName + "/edges.json").getPath();

    JSONDataSource source = new JSONDataSource(graphPath, vertexPath, edgePath, config);

    LogicalGraph graph = source.getLogicalGraph();

    JSONObject jsonObject = new JSONObject();

    //compute the vertex and edge property keys and return them
    try {
      jsonObject.put("vertexKeys", getVertexKeys(graph));
      jsonObject.put("edgeKeys", getEdgeKeys(graph));
      jsonObject.put("vertexLabels", getVertexLabels(graph));
      jsonObject.put("edgeLabels", getEdgeLabels(graph));
      return Response.ok(jsonObject.toString()).build();
    } catch (Exception e) {
      e.printStackTrace();
      // if any exception is thrown, return an error to the client
      return Response.serverError().build();
    }
  }

  /**
   * Takes any given graph and reates a JSONArray containing the vertex property keys and a boolean,
   * specifying it the property has a numerical type.
   * @param graph input graph
   * @return  JSON array with property keys and boolean, that is true if the property type is
   * numercial
   * @throws Exception if the collecting of the distributed data fails
   */
  private JSONArray getVertexKeys(LogicalGraph graph) throws Exception {

    Set<Tuple3<String, String, Boolean>> vertexKeys = graph.getVertices()
      .flatMap(new PropertyKeyMapper<Vertex>())
      .groupBy(1)
      .reduceGroup(new LabelGroupReducer())
      .reduce(new MergeToSet<Tuple3<String, String, Boolean>>())
      .collect()
      .get(0);

    return buildArrayFromKeys(vertexKeys);
  }

  /**
   * Takes any given graph and reates a JSONArray containing the edge property keys and a boolean,
   * specifying it the property has a numerical type.
   * @param graph input graph
   * @return  JSON array with property keys and boolean, that is true if the property type is
   * numercial
   * @throws Exception if the collecting of the distributed data fails
   */
  private JSONArray getEdgeKeys(LogicalGraph graph) throws Exception {

    Set<Tuple3<String, String, Boolean>> edgeKeys = graph.getEdges()
      .flatMap(new PropertyKeyMapper<Edge>())
      .groupBy(1)
      .reduceGroup(new LabelGroupReducer())
      .reduce(new MergeToSet<Tuple3<String, String, Boolean>>())
      .collect()
      .get(0);

    return buildArrayFromKeys(edgeKeys);
  }

  /**
   * Convenience method.
   * Takes a set of tuples of property keys and booleans, specifying if the property is numerical,
   * and creates a JSON array containing the same data.
   * @param keys set of tuples of property keys and booleans, that are true if the property type
   *             is numerical
   * @return JSONArray containing the same data as the input
   * @throws JSONException if the construction of the JSON fails
   */
  private JSONArray buildArrayFromKeys(Set<Tuple3<String, String, Boolean>> keys)
    throws JSONException {
    JSONArray keyArray = new JSONArray();
    for(Tuple3<String, String, Boolean> key : keys) {
      JSONObject keyObject = new JSONObject();
      keyObject.put("labels", key.f0);
      keyObject.put("name", key.f1);
      keyObject.put("numerical", key.f2);
      keyArray.put(keyObject);
    }
    return keyArray;
  }

  private JSONArray getVertexLabels(LogicalGraph graph) throws Exception {
    Set<String> vertexLabels = graph.getVertices()
      .map(new LabelMapper<Vertex>())
      .reduce(new LabelReducer())
      .collect()
      .get(0);

    return buildArrayFromLabels(vertexLabels);
  }

  private JSONArray getEdgeLabels(LogicalGraph graph ) throws Exception {
    Set<String> edgeLabels = graph.getEdges()
      .map(new LabelMapper<Edge>())
      .reduce(new LabelReducer())
      .collect()
      .get(0);

    return buildArrayFromLabels(edgeLabels);
  }

  private JSONArray buildArrayFromLabels(Set<String> labels) {
    JSONArray labelArray = new JSONArray();
    for(String label : labels) {
       labelArray.put(label);
    }
    return labelArray;
  }

  /**
   * Takes a {@link GroupingRequest}, executes a grouping with the parameters it contains and
   * returns the results as a JSON.
   * @param request GroupingRequest send to the Server, containing the parameters for a
   *        {@link Grouping}.
   * @return a JSON containing the result of the executed Grouping, a graph
   * @throws Exception if the collecting of the distributed data fails
   */
  @POST
  @Path("/data")
  @Produces("application/json;charset=utf-8")
  public Response getData(GroupingRequest request) throws Exception {

    System.out.println(request.toString());

    //load the database
    String dbName = request.getDbName();

    String graphPath =
      Server.class.getResource("/data/" + dbName + "/graphs.json").getPath();
    String vertexPath =
      Server.class.getResource("/data/" + dbName + "/vertices.json").getPath();
    String edgePath =
      Server.class.getResource("/data/" + dbName + "/edges.json").getPath();

    JSONDataSource source = new JSONDataSource(graphPath, vertexPath, edgePath, config);

    GraphCollection collection = source.getGraphCollection();

    //ToDo das geht besser
    GradoopId graphId = collection.getGraphHeads().collect().get(0).getId();

    LogicalGraph graph = source.getGraphCollection().getGraph(graphId);

    graph = graph.subgraph(
      new LabelFilter<Vertex>(request.getVertexFilters()),
      new LabelFilter<Edge>(request.getEdgeFilters()));

    //construct the grouping with the parameters send by the request
    Grouping.GroupingBuilder builder = new Grouping.GroupingBuilder();
    int position;
    position = ArrayUtils.indexOf(request.getVertexKeys(), "label");
    if(position > -1) {
      builder.useVertexLabel(true);
      request.setVertexKeys((String[])ArrayUtils.remove(request.getVertexKeys(), position));
    }
    builder.addVertexGroupingKeys(Arrays.asList(request.getVertexKeys()));


    position = ArrayUtils.indexOf(request.getEdgeKeys(), "label");
    if(position > -1) {
      builder.useEdgeLabel(true);
      request.setEdgeKeys((String[])ArrayUtils.remove(request.getEdgeKeys(), position));
    }
    builder.addEdgeGroupingKeys(Arrays.asList(request.getEdgeKeys()));
    String[] vertexAggrFunc = request.getVertexAggrFunc().split(" ");

    switch (vertexAggrFunc[0]) {
    case "max":
      builder.addVertexAggregator(new MaxAggregator(vertexAggrFunc[1], "max"));
      break;
    case "min":
      builder.addVertexAggregator(new MinAggregator(vertexAggrFunc[1], "min"));
      break;
    case "sum":
      builder.addVertexAggregator(new SumAggregator(vertexAggrFunc[1], "sum"));
      break;
    default:
      builder.addVertexAggregator(new CountAggregator());
      break;
    }

    String[] edgeAggrFunc = request.getEdgeAggrFunc().split(" ");
    switch (edgeAggrFunc[0]) {
    case "max":
      builder.addEdgeAggregator(new MaxAggregator(edgeAggrFunc[1], "max"));
      break;
    case "min":
      builder.addEdgeAggregator(new MinAggregator(edgeAggrFunc[1], "min"));
      break;
    case "sum":
      builder.addEdgeAggregator(new SumAggregator(edgeAggrFunc[1], "sum"));
      break;
    default:
      builder.addEdgeAggregator(new CountAggregator());
      break;
    }

    // by default, we use the group reduce strategy
    builder.setStrategy(GroupingStrategy.GROUP_REDUCE);

    graph = builder.build().execute(graph);

    // specify the output collections
    List<GraphHead> resultHead = new ArrayList<>();
    List<Vertex> resultVertices = new ArrayList<>();
    List<Edge> resultEdges = new ArrayList<>();

    graph.getGraphHead().output(new LocalCollectionOutputFormat<>(resultHead));

    graph.getVertices().output(new LocalCollectionOutputFormat<>(resultVertices));

    graph.getEdges().output(new LocalCollectionOutputFormat<>(resultEdges));


    try {
      ENV.execute();

      // build the response JSON from the collections
      CytoJSONBuilder cytoBuilder = new CytoJSONBuilder();

      String json = cytoBuilder.getJSON(resultHead.get(0), resultVertices, resultEdges);

      return Response.ok(json).build();

    } catch (Exception e) {
      e.printStackTrace();
      // if any exception is thrown, return an error to the client
      return Response.serverError().build();
    }
  }
}