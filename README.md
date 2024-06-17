# bcbridge
With bcbridge you can redirect java code. This means if a function calls goes to a() and you need it to go to b(), 
all you need is a simple YAML configuration.  
You can load two different java apps and redirect calls between them.  
So for example, if you have two java apps that uses the network to communicate, but you are on a special environment 
where you want them to run as a single application and call each other directly, you can just load both on bcbridge.  
You can configure what system properties, environment variables and command line arguments each application will get.     

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
Use different environment variables for apps running on the same environment.  
Different system properties or environment variables for each app on same process.  
Debug calls.  
Observable run.  
Have a single process instead of many process.  
Avoid network calls and use direct call.  

# How

Run bcbridge passing the configuration YAML as parameter.  
`java -jar bcbridge.jar myConfig.yaml`

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
    environmentVariables:
      - name: "property1"
        value: "value1"
    stdout: "/path/to/app1/logs/app1.log"
  - name: "App2"
    dependencies: ["App1"]
    jarsPath: "/path/to/app2/jars"
    mainClass: "com.example.App2Main"
    commandArguments: ["d", "e", "f"]
    systemProperties:
      - name: "property1"
        value: "anotherValue1"
      - name: "property2"
        value: "anotherValue2"
    environmentVariables:
      - name: "property1"
        value: "anotherValue1"
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