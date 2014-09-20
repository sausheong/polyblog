import org.zeromq.ZMQ;
import java.util.UUID;
import java.util.Calendar;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.ParseException;
import org.json.simple.parser.JSONParser;
import java.sql.*;
 
public class AddPost {

  public static void main(String[] args) {
    ZMQ.Context context = ZMQ.context(1);
    String routeid = "POST/_/post";
    String identity = UUID.randomUUID().toString();
    
    ZMQ.Socket socket = context.socket(ZMQ.REQ);
    socket.setIdentity(identity.getBytes());
    socket.connect ("tcp://localhost:4321");
 
    System.out.printf("%s - (%s) responder ready\n", routeid, identity);
    
    socket.send(routeid, 0);
    try {
      while (true) {
        String request = socket.recvStr();        
        JSONParser parser = new JSONParser();
        try {
          Object obj = parser.parse(request);
          JSONObject json = (JSONObject)obj;
          JSONObject form = (JSONObject)json.get("PostForm");
          String title = ((JSONArray)form.get("title")).get(0).toString();
          String content = ((JSONArray)form.get("content")).get(0).toString();
          
          Connection connection = connectToDatabaseOrDie();
          String stmt = "INSERT INTO posts (uuid, created_at, title, content) " +
          		          " VALUES(?, ?, ?, ?)";
          PreparedStatement pst = connection.prepareStatement(stmt);
          
          pst.setString(1, UUID.randomUUID().toString());
          Calendar calendar = Calendar.getInstance();
          Timestamp now = new Timestamp(calendar.getTime().getTime());
          pst.setTimestamp(2, now);
          pst.setString(3, title);
          pst.setString(4, content);
          pst.executeUpdate();          
          
        } 
        catch(Exception e) {
          e.printStackTrace();
        }        
        
        socket.send(routeid, ZMQ.SNDMORE);
        socket.send("302", ZMQ.SNDMORE);
        socket.send("{\"Location\": \"/_/\"}", ZMQ.SNDMORE);
        socket.send("");
      }      
    } catch (Exception e) {
      socket.close();
      context.term();      
    } 
  }
  
  private static Connection connectToDatabaseOrDie() {
    Connection conn = null;
    try {
      Class.forName("org.postgresql.Driver");
      String url = "jdbc:postgresql://localhost:5432/polyblog";
      conn = DriverManager.getConnection(url,"polyblog", "polyblog");
    }
    catch (ClassNotFoundException e) {
      e.printStackTrace();
      System.exit(1);
    }
    catch (SQLException e) {
      e.printStackTrace();
      System.exit(2);
    }
    return conn;
  }
  
  
  
}