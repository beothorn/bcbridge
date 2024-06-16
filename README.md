# bcode-bridge
Redirect java code.

Under construction.  

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

Override system properties and environment variables.  
Different system properties or environment variables for each app on same process.  
Debug calls.  
Observable run.  
Have a single process instead of many process.  
Avoid network calls and use direct call.  

# Example 

There is an App1 and an App2. The call on App1 on class com.example.App1Main method foo will be redirected to App2 on 
class com.example.App2Main method fooWithLog.  
Console outputs will be redirected to `/path/to/app1/logs/`.  
System properties property1 and property2 will be overwritten.  


```yaml
applications:
  - name: "App1"
    jarsPath: "/path/to/app1/jars"
    mainClass: "com.example.App1Main"
    commandArguments: ["a", "b", "c"]
    systemProperties:
      - name: "property1"
        value: "value1"
      - name: "property2"
        value: "value2"
    stdout: "/path/to/app1/logs/app1.log"
  - name: "App2"
    dependencies: ["App1"]
    jarsPath: "/path/to/app2/jars"
    mainClass: "com.example.App2Main"
    commandArguments: ["a", "b", "c"]
    systemProperties:
      - name: "property1"
        value: "value1"
      - name: "property2"
        value: "value2"
    stdout: "/path/to/app2/logs/app2.log"
    redirections:
      - sourceApplication: "App1"
        sourceMethod: "com.example.App1Main#foo"
        destinationMethod: "com.example.App2Main#fooWithLog"
      - sourceApplication: "App2"
        sourceMethod: "com.example.App2Main#foobar"
        destinationMethod: "com.example.App2Main#redirectToItselfFooBar"
```

# Issues

Redirected class containing method needs to have a no argument constructor.  
Only methods with standard type parameters.