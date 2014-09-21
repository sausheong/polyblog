# PolyBlog
### Or How to Create A Web App with 3 Different Programming Languages

This is the story of how you can create a web web app in 3 different programming languages. As you might notice as you read further, the web app created here is contrived, unlikely and overly complicated. You would probably never write a simple blog app with 3 different programming languages, and really, you shouldn't. 

However you'd likely want to write web apps that can scale whenever you want it to, and also evolve alongside with you and your team (assuming, of course, that you have a team). 

The web app I will show you uses [Polyglot](https://github.com/sausheong/polyglot), a framework for writing web web apps with multiple programming languages. Naturally, the first thing you'd need to do is to install it.

## Install Polyglot

First, clone the [Polyglot](https://github.com/sausheong/polyglot) repository, and follow the instructions to install it. Mostly this involves compiling Polyglot for your platform (it's developed in Go). Once you have done that, copy out the `polyglot`, `broker` and `config.json` files into your web app directory.

A Polyglot web app is broken down into 3 different components (no, they are not model, view and controller):

1. Acceptor
2. Broker
3. Responder

The Polyglot architecture is simple -- the acceptor accepts requests (usually from the browser), which passes it to the broker. The broker determines which responder it should send the request to from the route, and sends the request to an appropriate responder. There are usually more than 1 responder for a single route. The responder then processes the request and returns a response to the broker, which then sends it to the calling acceptor.

The `polyglot` file you have copied out to your web app directory is the acceptor, which accepts requests from your browser. You can configure it using the `config.json` file. The `broker` file is the broker, which determines which responder the request should be sent to. As you can see, both the acceptor and broker are already provided by Polyglot. What we need to do are just the responders. 

Simple, right?

## So what does this web app do anyway?

Now we have 2/3 of our web web app done (just kidding!), we can start thinking what we actually want it to do. What our app should do is to allow someone to create a blog post (consisting of a title and the content of the post), and also to show all previously created blog posts in a list. Easy enough, and if you've done any sort of web app development before you'll likely come to the same idea that we will need 3 routes:

1. Show the add post form (GET)
2. Create the post from the form data (POST)
3. Show all posts (GET)

Now you would probably say at this point in time that this is not really usable as a blog app and you would be right. However, many web apps are created the same way -- create a minimal viable product (MVP) first, then evolve it to what you eventually want. 

## Create the database tables

Before we jump into writing the responders, let's talk about the database tables. For this web app, the database table we'll need is pretty simple -- we just need a table to store all the posts that are being created. We'll be using Postgres for that.

To create database, I created a simple script in the `ruby` directory called `setup`:

```
psql -h localhost -c "create user polyblog with password 'polyblog'"
psql -h localhost -c "drop database if exists polyblog"
psql -h localhost -c "create database polyblog"
psql -h localhost -c "grant all privileges on database polyblog to polyblog"
```

I could have of course, also created the table using SQL directly, but I chose to create the table using a Ruby gem called [Sequel](https://github.com/jeremyevans/sequel) (remember to run `bundle install` before you begin). I created a directory called `migrations` to store all the migration files. There's really only one file here, which is `01_setup.rb`. We can progressively add more migration files as we evolve our web app but let's start with this:

```ruby
Sequel.migration do
  change do  
    create_table :posts do
      primary_key :id
      String :uuid, unique: true
      DateTime :created_at
      String :title
      String :content, text: true      
    end
  end  
end
```

Then run this in the command line (or run the `migrate` script):

```
sequel -m migrations postgres://polyblog:polyblog@localhost:5432/polyblog
```

And we're done! Check with `psql` if your table exists.

## Show create post form

Let's continue. The first responder we'll create is the responder to show the post creation form. This is what we want to show in the end:

![Alt text](/doc_images/add_post.png)


We'll use Ruby as the first language for the responder. Libraries we'll use for this responder include

* [HAML](http://haml.info/) - to generate the HTML response
* [Sequel](https://github.com/jeremyevans/sequel) - to access the Postgres database
* [FFI-RZMQ](https://github.com/chuckremes/ffi-rzmq) - to communicate with the Polyglot broker

This is how the Ruby responder looks like:

```ruby
require 'securerandom'
require 'bundler'
Bundler.require
require './ruby_helper'

include Helper

broker = "tcp://localhost:4321"
routeid = "GET/_/post/new"
identity = SecureRandom.uuid

puts "#{routeid} - (#{identity}) responder ready."

ctx = ZMQ::Context.new
client = ctx.socket ZMQ::REQ
client.identity = identity
client.connect broker

client.send_string routeid

loop do
  request = String.new
  client.recv_string request
  content = haml("views/post.new.haml", "views/layout.haml")
  response = [routeid, "200", "{\"Content-Type\": \"text/html\"}", content]
  client.send_strings response
end
```

As you can see, the steps are quite straightforward (this is a pattern you'll see in the other responders):

1. Connect to the broker
2. Register the responder
3. Loop to receive requests, process them and send a response

Going into the details:

1. Define the route ID - it must start with a HTTP method, followed by "/_/" but everything else is up to you
2. Create a unique UUID - this can be a randomly generated UUID or something you hardcode, just make sure it's unique to the whole web app
3. Connect to the broker (which is at port 4321) using a ZeroMQ Request socket
4. Send the route ID to the broker to register the responder
5. Loop indefinitely to:
    1. Receive the request
    2. Process it (do what you want, or do nothing)
    3. Send a response in a sequence of messages:
        1. route ID
        2. HTTP response status (200, 302, 404 etc)
        3. JSON encoded headers
        4. Response body (your data)

The content for the response body is mostly going to be HTML so your responder should be able to generate the HTML string that is sent back as the response. 

To support other responders, I've refactored away the methods to generate the HTML response:

```ruby
require 'haml'

module Helper
  def haml(template, layout)
    Haml::Engine.new(File.read(layout)).render do
      Haml::Engine.new(File.read(template)).render
    end    
  end
end
``` 

What the responder does is simply to generate HTML to be displayed on the browser (using HAML) and return it  as part of the response.

## Create post

Next, we'll use Java to write a responder that creates a post. Let's look at the code first:

```java
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
```

The pattern is the same:

1. Connect to the broker
2. Register the responder
3. Loop to receive requests, process them and send a response

A couple of differences in this responder, compared to the Ruby responder:

1. In the Ruby responder we don't do anything with the request data, but here we extract the data from the form post and create the post using the form data
2. In the Ruby responder we return HTML to the browser, but here we send a 302 to the browser, with the location set in the header. instructing the browser to go to another URL i.e. the response is a redirect instruction to the browser

The code looks more complex than the one in the Ruby responder, but it's just that Java is more verbose than Ruby (and that we need to connect to the database first).

## Show all posts

Now that we have the posts, we'll write a responder that will show all posts in a single page. For this, we'll use Go. Here's the code:

```go
package main

import (	
	"fmt"
  "html/template"
  "time"
  "bytes"
  "code.google.com/p/go-uuid/uuid"
  _ "github.com/lib/pq"
  "github.com/jmoiron/sqlx"
  zmq "github.com/pebbe/zmq4"
)

const (
	ROUTEID = "GET/_/"
)

type Post struct {
  Id        int64
  Uuid      string
  CreatedAt time.Time `db:"created_at"`
  Title     string
  Content   string
}
func (p *Post) CreatedAtDate() string {
	return p.CreatedAt.Format("01-02-2006")
}


func main() {
  
  db, err := sqlx.Connect("postgres", "user=polyblog dbname=polyblog sslmode=disable")
  if err != nil {
      fmt.Println(err)
  }

	responder, _ := zmq.NewSocket(zmq.REQ)
	defer responder.Close()

	identity := uuid.New()
	responder.SetIdentity(identity)
	responder.Connect("tcp://localhost:4321")
	fmt.Printf(" %s - %s responder ready\n", ROUTEID, identity)
	responder.Send(ROUTEID, 0)

	for {
		_, err := responder.RecvMessage(0)
		if err != nil {
      fmt.Println("Error in receiving message:", err)
			break //  Interrupted
		}

    posts := []Post{}
    db.Select(&posts, "SELECT * FROM posts ORDER BY created_at DESC")  
    
  	t := template.New("posts")
  	t = template.Must(t.ParseGlob("goresp/*.html"))
    buf := new(bytes.Buffer)  
    t.Execute(buf, posts)    

    resp := []string{"200", "{\"Content-Type\": \"text/html\"}", buf.String(),}
		responder.SendMessage(ROUTEID, resp)
	}
}
```

Again, the pattern is the same:

1. Connect to the broker
2. Register the responder
3. Loop to receive requests, process them and send a response

I used the following Go libraries:

* [Go-UUID](https://godoc.org/code.google.com/p/go-uuid/uuid) - for generating the random UUID
* [SQLX](https://github.com/jmoiron/sqlx) - for querying and extracting posts from the database
* html/template - for parsing the HTML template and generating the HTML response

I used SQLX to query for the posts in the database, which is then populated into a struct to be used by `html/template` for generating the HTML response.

A snippet of the `posts.html` template:

```html
<div class='content'>
  {{ range .}}
    <div class="row">
      <div class="col-md-12">
        <span class="pull-right">{{ .CreatedAtDate }}</span>
        <h2>
          <a href="_/post/{{ .Uuid }}">{{ .Title }}</a>
        </h2>
      </div>
      <div class="col-md-12">
        {{ .Content }}
      </div>
      <div class="col-md-12"><hr/></div>
    </div>
  {{ end }}
</div>
```

`{{ range . }}` iterates through the slice of posts created by SQLX while the corresponding struct member is accessed using the dot operator. `.CreatedAtDate` though calls a function attached to the Post struct.

## Conclusion

As mentioned earlier, writing a web app this way is overly convoluted for a simple blog app. However, there are a number of benefits for using Polyglot to write a web app:

1. **Evolve your web app** - you don't need to create the whole web app all at one shot. Create it piecemeal and add new responders or take out old ones as necessary
2. **Scale your web app** - in this example, we're starting just 1 process for each responder. Polyglot allows you to start as many processes as you like for each responder, and will redirect the request to each one of them round-robin. You don't even need to add them all at one shot, you can add or remove the responders later when you need them
3. **Use what you like to create your responders** - as you can see, you can use different programming languages to write responders. In fact you don't even need to write the same responder using the same programming language. In the above example, you can write the same responder using 2 different languages and have them registered with the broker and the broker will send the requests to them in a round robin

Why multiple languages? Logically if you're the only programmer in the team, or if you have a small team with a focused set of skills, you can ust choose one programming language as the primary one. However, teams evolve and change over time, team members leave or join and the skillsets change as well.

When that happens, everyone needs to learn the same chosen programming language, because there's no other choice. What's worse, you need to use the same libraries even though newer libraries or tools are available, unless you migrate from an existing library to a new one. And as the platform and the libraries age, re-platforming or changing libraries become really painful.

With Polyglot, you can have the flexibility of slowly evolving the entire system from libraries to programming languages to the entire underlying platform. Just write new responders in the new platform while retaining the existing ones. And then move responders piecemeal to the new platform as and when needed. 

Polyglot is not the panacea to re-platforming woes, but it should reduce the pain or at least isolate or distribute it.


