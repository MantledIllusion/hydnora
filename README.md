# Hydnora
Hydnora is a small library for concurrent caching.

Hydnora Africana is an underground living plant. The only overground part is its insect-attracting 
flower whose small opening leads down into the plant, trapping entering insect. Instead of digesting the 
insects like carnivorous plants would, Hydrona just pollinates and releases them once it is mature.

```xml
<dependency>
    <groupId>com.mantledillusion.cache</groupId>
    <artifactId>hydnora</artifactId>
</dependency>

```

Get the newest version at  [mvnrepository.com/hydnora](https://mvnrepository.com/artifact/com.mantledillusion.cache/hydnora)

## Creating Caches
In order to implement a cache, the base class **HydnoraCache** has to be extended, providing two generic types:
- EntryIdType: The type of the key used to identify the cache's entries
- EntyType: The class-type of the entries stored by the cache

For example, creating a cache for book details, where the books are identified by ISBN number, might look as follows:

```java
public class BookCache extends HydnoraCache<String, Book> {
    
    private final BookService service;
    
    public BookCache(BookService service) {
        this.service = service;
    }
    
    @Override
    protected Book load(String isbn) throws Exception {
        return this.service.findBook(isbn);
    }
}
```

Note that Hydnora uses internally use a concurrent _**HashMap**_ for storing data; as a result, not only standard types like String might be used as the entry ID type, but rather any Java class, as long as it provides valid implementations of the __**Object**.equals()_ and _**Object**.hashCode()_ methods.

## Retrieving Data and Concurrency

Following the example, retrieving a book from the cache is a simple method call:

```java
Book harryPotterAndThePhilosophersStone = bookCache.get("9780590353403");
```

Upon being called with entries' ID, Hydnora will check if the element is currently cached or not:
- If it is, it will simply be returned. 
- If not, Hydnora will call the extended **_.load()_** method in order to cache the entry; the cache will also synchronize all access to the cache for every thread trying to retrieve the entry of that ID by locking them all until _**.load()**_ finishes execution.

Note that threads accessing the cache for any other entry ID are **not** locked and will be able to access their respective entries without being affected.

## Expiry

By default, no data in the cache ever expires. 

Using an expiry interval (by either using the advanced constructor or the setter for the interval), cache entries will be seen as expired if they are older than the given threshold:

```java
BookCache cache = new BookCache();
cache.setExpiryInterval(1000L);

Book loadedBook = cache.get("9780590353403"); // Entry not yet included in the cache; load it and return it
Book cachedBook = cache.get("9780590353403"); // Entry already cached, just return it
Thread.sleep(1000l);
Book reloadedBook = cache.get("9780590353403"); // Entry cached but expired; reload it and return it
```

It is important to know that **Hydnora does not use any scheduled task to cleanup cached entries**. As a result, expired entries will remain in the cache until their ID is used to retrieve them the next time after the expiry.

If needed, all expired entries can be removed from the cache by simply calling the **_.clean()_** method:

```java
BookCache cache = new BookCache();
cache.setExpiryInterval(1000L);

Book loadedBook = cache.get("9780590353403");

Thread.sleep(1000l);
cache.clean(); // Entry loaded before was already expired, so cache will be completely empty again
```

## Invalidation

There are multiple ways to invalidate Hydnora cache entries:

```java
BookCache cache = new BookCache();

Book harryPotterAndThePhilosophersStone = bookCache.get("9780590353403");
Book harryPotterAndChamberOfSecrets = bookCache.get("9781856136129");
Book harryPotterAndPrisonerOfAzkaban = bookCache.get("9780439136358");

cache.invalidate("9780590353403"); // By key; invalidates the first book
cache.invalidate((isbn, book) -> book.getTitle().contains("Azkaban")); // By predicate; invalidates the third book
cache.invalidate(); // By existence; invalidates all books, in this case: the second book
```

Unlike expiry, invalidating an entry will cause the entry to be removed from the cache immediately.

Note that invalidation synchronizes the whole cache, causing **all** threads trying to retrieve cache data to be locked until invalidation has commenced.

## Memory Leak Protection

Even if an expiry interval is used and manual calls invalidate entries from time to time, depending on the size of its entries, a highly used cache might still grow significantly, taking up a huge part (or even all) of the JVMs RAM.

To protect against such a memory leak, Hydnora supports 3 modes of how entries are referenced by the cache:
- STRONG (default): entries are referenced directly; entries are never garbage collected
- SOFT: entries are referenced using a **_SoftReference_**; entries are garbage collected when the JVM is threatened to run out of memory
- WEAK: entries are referenced using a **_WeakReference_**; entries might be garbage collected at will; when or how is up to the collector

While the default STRONG mode will be best for cache efficiency, the SOFT (and in some cases even WEAK) mode are highly recommendable in situations where the amount of possible entry data is high.