# bcode-bridge
Redirect java code.

Under construction. Nothing works yet.  

# What

Load different jars into separate class loaders.  
Run them in a single process.  
Instrument byte-code based on the configuration files.  
Redirect calls from one class loader to the other one.  
Redirect console output.  
Intercept file access.  
Intercept system properties.  
Interactive console with meta information.  

# Why

Debug calls.  
Observable run.  
Have a single process instead of many process.  
Avoid network calls and use direct call.  

# Example 

```yaml
applications:
  - name: "App1"
    jarsPath: "/path/to/app1/jars"
    mainClass: "com.example.App1Main"
    systemProperties:
      - name: "property1"
        value: "value1"
      - name: "property2"
        value: "value2"
    logFilePath: "/path/to/app1/logs/app1.log"
  - name: "App2"
    dependencies:
      - name: "App1"
    jarsPath: "/path/to/app2/jars"
    mainClass: "com.example.App2Main"
    systemProperties:
      - name: "property1"
        value: "value1"
      - name: "property2"
        value: "value2"
    logFilePath: "/path/to/app2/logs/app2.log"
    redirections:
      - sourceApplication: "App1"
        sourceMethod: "com.example.App1Main#foo(String, String, int)"
        destinationMethod: "com.example.App2Main#fooWithLog"

```
