package com.github.lechandonga.localobjectstorejava;

import com.github.lechandonga.localobjectstorejava.store.StorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StoreConfiguration {
}
