karaf-maven-plugin
==================

Usage
-----
### karaf:execute

#### Full name:
`org.jboss.soa:karaf-maven-plugin:1.0.0-SNAPSHOT:execute`

#### Description:
This maven plugin can execute OSGi commands via ssh client.

#### Attributes:

| Name      | Type      | Description  |
| ----------|---------| ------|
| host      | `String`  | Server host. <br />**Default:** localhost|
| port      | `int`     | Server port. <br />**Default:** 8101|
| user      | `String`  | Username. <br />**Default:** karaf|
| password  |`String`   | Password. <br />**Default:** karaf|
| attempts  |`int`      | Retry connection establishment (up to attempts times). <br />**Default:** 0|
| delay     |`int`      | Intra-retry delay. <br />**Default:** 2|
| commands  |`String[]` | OSGi commands. <br /> **Required** |


#### Example:

```xml
<plugin>
    <groupId>org.jboss.soa</groupId>
	<artifactId>karaf-maven-plugin</artifactId>
	<version>1.0.0-SNAPSHOT</version>
	<configuration>
		<commands>
			<param>features:install -v switchyard-bean</param>
			<param>features:install -v switchyard-camel</param>
			<param>features:list -i | grep switchyard</param>
		</commands>
	</configuration>
	<executions>
		<execution>
			<id>setup-karaf</id>
			<phase>pre-integration-test</phase>
			<goals>
				<goal>execute</goal>
			</goals>
		</execution>
	</executions>
</plugin>
```

OSGi commands - Quickstarts
---------------------------
* [Add new user](http://karaf.apache.org/manual/latest/users-guide/security.html)
~~~
jaas:manage  --realm karaf --module org.apache.karaf.jaas.modules.properties.PropertiesLoginModule
jaas:useradd jdoe secret
jaas:roleadd jdoe admin
jaas:update
~~~

* [Set up path to file settings.xml](http://karaf.apache.org/manual/latest/users-guide/configuration.html)
```
config:edit org.ops4j.pax.url.mvn
config:propset org.ops4j.pax.url.mvn.settings <path>
config:update
```
