This repository was made based on https://github.com/kbase/java_common in order to separate and cleanup code related to algorithm sorting keys in large JSON data using file caching. This code was written mostly by me (Roman Sutormin) with help of Gavin Price as part of KBase system. But it could be helpful to have it in independent repo without unnecessary dependencies and references to KBase infrastructure.

##Usage

```java
        OutputStream os = new FileOutputStream(new File("sorted.json"));
        new LowMemoryUTF8JsonSorter(new File("test.json"))
                .setMaxMemoryForKeyStoring(10000000).writeIntoStream(os);
        os.close();
```

##Dependencies

- Jackson FasterXML
- JUnit

##Contributions

Feel free to make your suggestions and pull requests.
