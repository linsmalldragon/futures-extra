### Futures-extra

Futures-extra is a set of small utility functions to simplify working with
Guava's ListenableFuture class

### Build status

[![Travis](https://api.travis-ci.org/spotify/futures-extra.svg?branch=master)](https://travis-ci.org/spotify/futures-extra)
[![Coverage Status](http://img.shields.io/coveralls/spotify/futures-extra/master.svg)](https://coveralls.io/r/spotify/futures-extra?branch=master)

### Maven central

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.spotify/futures-extra/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.spotify/futures-extra)

### Build dependencies
* Java 8 or higher
* Maven

### Runtime dependencies
* Java 6 or higher
* Guava 18.0 or higher

### Usage

Futures-extra is meant to be used as a library embedded in other software.
To import it with maven, use this:

    <dependency>
      <groupId>com.spotify</groupId>
      <artifactId>futures-extra</artifactId>
      <version>2.5.0</version>
    </dependency>

### Examples

#### Cleaner transforms for Java 8.
Java 8 introduced lambdas which can greatly reduce verbosity in code, which is
great when using futures and transforms. One drawback with lambdas though is
that when a lambda is supplied as an argument to a method with overloaded
parameters, the compiler may fail to figure out which variant of a method call
that is intended to be used.

Ideally, applying java 8 lambdas to Guava's Futures.transform() would look
something like this:
```java
public static <A, B> ListenableFuture<B> example(ListenableFuture<A> future) {
  return Futures.transform(future, a -> toB(a));
}
```

Unfortunately this doesn't actually work because Futures.transform has
two variants: one that takes a Function as its second parameter and one that
takes an AsyncFunction. The compiler can't determine which variant to use
without additional type information.

You could work around that by casting it like this:
```java
public static <A, B> ListenableFuture<B> example(ListenableFuture<A> future) {
  return Futures.transform(future, (Function<A, B>) a -> toB(a));
}
```

With futures-extra you can do this instead:
```java
public static <A, B> ListenableFuture<B> example(ListenableFuture<A> future) {
  return FuturesExtra.syncTransform(future, a -> toB(a));
}
```

This is just a simple delegating method that explicitly calls
Futures.transform(future, Function). There is also a corresponding
FuturesExtra.asyncTransform that calls Futures.transform(future, AsyncFunction).

#### Joining multiple futures

A common use case is waiting for two or more futures and then transforming the
result to something else. You can do this in a couple of different ways, here
are two of them:

The examples are for Java 8, but they also work for Java 6 and 7 (though it
becomes more verbose).

```java
final ListenableFuture<A> futureA = getFutureA();
final ListenableFuture<B> futureB = getFutureB();

ListenableFuture<C> ret = Futures.transform(Futures.allAsList(futureA, futureB),
    (Function<List<?>, C>)list -> combine((A) list.get(0), (B) list.get(1));
```
where combine is a method with parameters of type A and B returning C.

This one has the problem that you have to manually make sure that the casts and
ordering are correct, otherwise you will get ClassCastException.

You could also access the futures directly to avoid casts:
```java
final ListenableFuture<A> futureA = getFutureA();
final ListenableFuture<B> futureB = getFutureB();

ListenableFuture<C> ret = Futures.transform(Futures.allAsList(futureA, futureB),
    (Function<List<?>, C>)list -> combine(Futures.getUnchecked(futureA), Futures.getUnchecked(futureB));
```
Now you instead need to make sure that the futures in the transform input are
the same as the ones you getUnchecked. If you fail to do this, things may work
anyway (which is a good way of hiding bugs), but block the thread, actually
removing the asynchronous advantage. Even worse - the future may never finish,
blocking the thread forever.

To simplify these use cases we have a couple of helper functions:
```java
final ListenableFuture<A> futureA = getFutureA();
final ListenableFuture<B> futureB = getFutureB();

ListenableFuture<C> ret = FuturesExtra.syncTransform2(futureA, futureB,
    (a, b) -> combine(a, b));
```

This is much clearer! We don't need any type information because the lambda can
infer it, and we avoid the potential bugs that can occur as a result of the
first to examples.

The tuple transform can be used up to 6 arguments named syncTransform2() through
syncTransform6(). If you need more than that you could probably benefit from
some refactoring, but you can also use FuturesExtra.join():

```java
final ListenableFuture<A> futureA = getFutureA();
final ListenableFuture<B> futureB = getFutureB();

final ListenableFuture<JoinedResults> futureJoined = FuturesExtra.join(futureA, futureB);
return Futures.transform(futureJoined,
    joined -> combine(joined.get(futureA), joined.get(futureB)));
```

This supports an arbitrary number of futures, but is slightly more complex.
However, it is much safer than the first two examples, because joined.get(...)
will fail if you try to get the value of a future that was not part of the
input.

#### Timeouts

Sometimes you want to stop waiting for a future after a specific timeout and to
do this you generally need to have some sort of scheduling involved. To simplify
that, you can use this:
```java
final ListenableFuture<A> future = getFuture();
final ListenableFuture<A> futureWithTimeout = FuturesExtra.makeTimeoutFuture(scheduledExecutor, future, 100, TimeUnit.MILLISECONDS);
```

#### Select

If you have some futures and want to succeed as soon as the first one succeeds,
you can use select:
```java
final List<ListenableFuture<A>> futures = getFutures();
final ListenableFuture<A> firstSuccessful = FuturesExtra.select(futures);
```

#### Success/Failure callbacks

You can attach callbacks that are run depending on the results of a future:
```java
final ListenableFuture<A> future = getFuture();
FuturesExtra.addCallback(future, System.out::println, Throwable::printStackTrace);
```


Alternatively, if you are only interested in either successful or failed 
results of a future, you can use:
```java
final ListenableFuture<A> future = getFuture();
FuturesExtra.addSuccessCallback(future, System.out::println);
```

```java
final ListenableFuture<B> future = getFuture();
FuturesExtra.addFailureCallback(future, System.out::println);
```

#### Concurrency limiting

If you want to fire of a large number of asynchronous requests or jobs,
it can be useful to limit how many will run concurrently.
To help with this, there is a new class called `ConcurrencyLimiter`.
You use it like this:

```java
int maxConcurrency = 10;
ConcurrencyLimiter<T> limiter = ConcurrencyLimiter.create(maxConcurrency);
for (int i = 0; i < 1000; i++) {
  ListenableFuture<T> future = limiter.add(() -> createFuture());
}
```
The concurrency limiter will ensure that at most 10 futures are created and
incomplete at the same time. All the jobs that are passed into
`ConcurrencyLimiter.add()` will wait in a queue until the concurrency is below
the limit.

The jobs you pass in should not be blocking or be overly CPU intensive.
If that is something you need you should let your ConcurrencyLimiter jobs push
the work on a thread pool.

The internal queue is unbounded, so you have to manage fast-failing yourself.
One idea is to look at the queue size (`limiter.numQueued()`) to decide if
it's time to fast-fail.

#### Completed futures

In some cases you want to extract the value (or exception) from the future and you know that
the future is completed so it won't be a blocking operation.

You could use these methods for that, but they will also block if the future is not complete which may lead to
hard to find bugs.
```java
T value = future.get();
T value = Futures.getUnchecked(future);
```

Instead you can use these methods which will never block but instead immediately
throw an exception if the future is not completed. This is typically useful in unit tests
(where futures should be immediate) and in general future callbacks/transforms where you know that a
specific future must be completed for this codepath to be triggered.
```java
T value = FuturesExtra.getCompleted(future);
Throwable exc = FuturesExtra.getException(future);
```

#### JDK 8 CompletableFuture <-> ListenableFuture Conversion

* From `ListenableFuture` To JDK 8 `CompletableFuture`

```java
ListenableFuture<V> listenable = getFuture();
CompletableFuture<V> completable = CompletableFuturesExtra.toCompletableFuture(listenable);
```

* From JDK 8 `CompletableFuture` To `ListenableFuture`

```java
CompletableFuture<V> completable = getFuture();
ListenableFuture<V> listenable = CompletableFuturesExtra.toListenableFuture(completable);
```
