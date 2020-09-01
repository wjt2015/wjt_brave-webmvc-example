# wjt_brave-webmvc-example
练习zipkin_brave

Once the services are started, open http://localhost:8081/

This will call the backend (http://localhost:9000/api) and show the result, which defaults to a formatted date.
Next, you can view traces that went through the backend via http://localhost:9411/?serviceName=backend

This is a locally run zipkin service which keeps traces in memory


$ cd webmvc4
$ mvn jetty:run -Pfrontend
$ mvn jetty:run -Pbackend





