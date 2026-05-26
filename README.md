# Authentication and Authorization using JWT with Spring WebFlux and Spring Security Reactive

## Nice Docs to Read First

Before getting started, I suggest you go through the following references:

[Spring WebFlux](https://docs.spring.io/spring-framework/reference/web/webflux.html)

[Spring Security Reactive](https://docs.spring.io/spring-security/site/docs/current/reference/htmlsingle/#reactive-applications)

[Spring Security Architecture](https://spring.io/guides/topicals/spring-security-architecture)

## Enable Spring WebFlux Security
First enable WebFlux Security in your application with `@EnableWebFluxSecurity`

```java
@SpringBootApplication
@EnableWebFluxSecurity
public class SecuredRestApplication {
....
}
```

## Create an InMemory UserDetailsService

Define a custom `UserDetailsService` bean where a user with a password and
initial roles is added:


```java
@Bean
    public MapReactiveUserDetailsService userDetailsRepository() {
        UserDetails user = User.withDefaultPasswordEncoder()
                .username("user")
                .password("user")
                .roles("USER", "ADMIN")
                .build();
        return new MapReactiveUserDetailsService(user);
    }
```

In this example user information will be stored in memory using a `Map`, but it can be replaced by different strategies.

Before getting a JSON Web Token, a user should use another authentication mechanism, for example HTTP Basic Authentication; provided the right credentials, a JWT will be issued, which can be used to perform future API calls by changing the `Authentication` method from Basic to Bearer.


## Starting from Basic Authentication

Below there's a simple way to define Basic Authentication with Spring Security. Customization is needed in order to return a JWT on successful authentication.

```java
@Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            .authorizeExchange(exchanges -> exchanges
                .anyExchange().authenticated())
            .httpBasic(Customizer.withDefaults()); // Pure basic is not enough for us!

        return http.build();
    }
```

## Inspect AuthenticationWebFilter, improvise, adapt, overcome

With Spring Reactive, requests go through a chain of filters; each filter can approve or discard requests according to different rules. Advantage is taken to perform request authentication.
Different types of `WebFilter` are grouped by a `WebFilterChain`; in Spring Security there's `AuthenticationWebFilter`, which outlines how authentication should be performed on requests matching certain criteria.

`AuthenticationWebFilter` implements all the required behavior for Basic Authentication; take a look at it:


```java
public class AuthenticationWebFilter implements WebFilter {

	private final ReactiveAuthenticationManager authenticationManager;

	private ServerAuthenticationSuccessHandler authenticationSuccessHandler = new WebFilterChainServerAuthenticationSuccessHandler(); 
  // WE NEED A DIFFERENT SUCCESS HANDLER!!!!!!

	private Function<ServerWebExchange, Mono<Authentication>> authenticationConverter = new ServerHttpBasicAuthenticationConverter();

	private ServerAuthenticationFailureHandler authenticationFailureHandler = new ServerAuthenticationEntryPointFailureHandler(new HttpBasicServerAuthenticationEntryPoint());

	private ServerSecurityContextRepository securityContextRepository = NoOpServerSecurityContextRepository.getInstance();

	private ServerWebExchangeMatcher requiresAuthenticationMatcher = ServerWebExchangeMatchers.anyExchange();

....
```

The behavior that needs to be changed is what happens once a user has been authenticated using user/password credentials.
The `WebFilterChainServerAuthenticationSuccessHandler` will pass the request through the filter chain. A custom implementation is needed in this step, where a JSON Web Token is generated and added to the response; the exchange then continues on its way.


## Create a custom SuccessHandler to make Basic Authentication return a JSON Web Token

Create a custom `ServerAuthenticationSuccessHandler`; this handler is executed once the authentication with user/password has been successful, and it receives the current exchange and `Authentication` object. A JWT is generated using the `Exchange` and `Authentication` object. In this way `BasicAuthenticationSuccessHandler` implements the desired behavior:

```java
...
 @Override
    public Mono<Void> onAuthenticationSuccess(WebFilterExchange webFilterExchange, Authentication authentication) {
    // Create and attach a JWT before passing the exchange to the filter chain
        ServerWebExchange exchange = webFilterExchange.getExchange();
        exchange.getResponse()
                .getHeaders()
                .add(HttpHeaders.AUTHORIZATION, getHttpAuthHeaderValue(authentication));
        return webFilterExchange.getChain().filter(exchange);
    }
...
```
The response from the current exchange is updated with an HTTP Authorization header that carries a new JWT containing data from the `Authentication` object.


## Create a Basic Authentication filter that returns a JWT

Now create a new `AuthenticationWebFilter` with a custom handler:

```java
...
UserDetailsRepositoryReactiveAuthenticationManager authManager;
        AuthenticationWebFilter basicAuthenticationFilter;
        ServerAuthenticationSuccessHandler successHandler;
        
        authManager = new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsRepository());
        successHandler = new  BasicAuthenticationSuccessHandler();

        basicAuthenticationFilter = new AuthenticationWebFilter(authManager);
        basicAuthenticationFilter.setAuthenticationSuccessHandler(successHandler);

...
```


## Add this filter to ServerHttpSecurity


Add this to our `ServerHttpSecurity`:

```java
...
http
                .authorizeExchange(exchanges -> exchanges
                    .pathMatchers("/login", "/").authenticated())
                .addFilterAt(basicAuthenticationFilter, SecurityWebFiltersOrder.HTTP_BASIC)
...
```

The functionality that returns a JWT when authenticating using a username and password is now implemented.


## Handle Requests with a Bearer Token Authorization Header

Now let's build the functionality that will handle a request whose HTTP Authorization header contains a Bearer token.
Just as the `AuthenticationWebFilter` was customized before, we customize another one to create a new filter.

When using JWT, all the information needed to authenticate and authorize a user lives within the token.
Perform the following steps:

Filter requests that contain a Bearer token in their HTTP Authorization header, verify that the token is well formed, confirm that it has a valid signature, and then build an `Authentication` object from all the information contained in the payload. If the JWT is invalid, no `Authentication` is produced, resulting in an unauthorized response.

Because all the information needed is contained in the JWT payload, every invalid token is rejected in the filtering step; but the contract defined by `AuthenticationWebFilter` requires a non-null `AuthenticationManager`. Create a dummy manager that authenticates all exchanges. Why? Because invalid JWTs never produce an `Authentication` object and therefore never make it into this step.


## Generate an Authentication object using only the information contained in the token

Create a converter `ServerHttpBearerAuthenticationConverter` that takes a `ServerWebExchange` and returns an `Authentication` object created with the information extracted from the token:

```java
...
 public Mono<Authentication> apply(ServerWebExchange serverWebExchange) {
        return Mono.justOrEmpty(serverWebExchange)
                .flatMap(AuthorizationHeaderPayload::extract)
                   .filter(matchBearerLength)
                .flatMap(isolateBearerValue)
                .flatMap(jwtVerifier::check)
                .flatMap(UsernamePasswordAuthenticationBearer::create).log();
    }
...
```

## Create a dummy AuthenticationManager

Now implement a dummy `AuthenticationManager` called `BearerTokenReactiveAuthenticationManager`:

```java
...
 public Mono<Authentication> authenticate(Authentication authentication) {
        return Mono.just(authentication);
    }
  
...
```

## Add the new filter to ServerHttpSecurity

Finally, chain this filter into the `ServerHttpSecurity` configuration object:

```java
...
public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {

        http
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/login", "/").authenticated()
                        .pathMatchers("/api/**").authenticated())
                .addFilterAt(basicAuthenticationFilter(), SecurityWebFiltersOrder.HTTP_BASIC)
                .addFilterAt(bearerAuthenticationFilter(), SecurityWebFiltersOrder.AUTHENTICATION);

        return http.build();
    }
...
```


## Create a REST Controller and configure access rules

```java
...
 @GetMapping("/api/private")
    @PreAuthorize("hasRole('USER')")
    public Flux<FormattedMessage> privateMessage() {
        return messageService.getCustomMessage("User");
    }

...
```


## Run the Application

With Maven
```shell
$ mvn spring-boot:run
```

With Gradle
```shell
$ ./gradlew bootRun
```


## Test it

Login using HTTP Basic

```shell
$ curl -v -u user:user localhost:8080/login
```

Inspect the response and find the Authorization header.
It should look like:

```shell
Authorization: Bearer eyJhbGciOiJIUzI1Ni.....
```

Use that in another request:

```shell
$ curl -v -H "Authorization: Bearer eyJhbGciOiJIUzI1Ni....." localhost:8080/api/admin
```

You should be able to consume the API.

## That's all

Hope you enjoy it.
