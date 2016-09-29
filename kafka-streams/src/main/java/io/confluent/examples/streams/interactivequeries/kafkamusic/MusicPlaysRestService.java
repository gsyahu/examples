/**
 * Copyright 2016 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.confluent.examples.streams.interactivequeries.kafkamusic;

import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.confluent.examples.streams.avro.Song;
import io.confluent.examples.streams.interactivequeries.HostStoreInfo;
import io.confluent.examples.streams.interactivequeries.MetadataService;

/**
 *  A simple REST proxy that runs embedded in the {@link KafkaMusicExample}. This is used to
 *  demonstrate how a developer can use the Interactive Queries APIs exposed by Kafka Streams to
 *  locate and query the State Stores within a Kafka Streams Application.
 */
@Path("kafka-music")
public class MusicPlaysRestService {

  private final KafkaStreams streams;
  private final int port;
  private final MetadataService metadataService;
  private final Client client = ClientBuilder.newClient();
  private Server jettyServer;
  private LongSerializer serializer = new LongSerializer();


  MusicPlaysRestService(final KafkaStreams streams, final int port) {
    this.streams = streams;
    this.port = port;
    this.metadataService = new MetadataService(streams);
  }


  @GET
  @Path("/charts/genre/{genre}")
  @Produces(MediaType.APPLICATION_JSON)
  public List<SongPlayCountBean> genreCharts(@PathParam("genre") final String genre) {

    // Lookup the KeyValueStore with the provided storeName
    final ReadOnlyKeyValueStore<String, KafkaMusicExample.TopFiveSongs> topFiveByGenre = streams.store
        (KafkaMusicExample.TOP_FIVE_SONGS_BY_GENRE_STORE, QueryableStoreTypes
        .<String, KafkaMusicExample.TopFiveSongs>keyValueStore());

    return topFiveSongs(genre.toLowerCase(), topFiveByGenre);

  }

  @GET
  @Path("/charts/top-five")
  @Produces(MediaType.APPLICATION_JSON)
  public List<SongPlayCountBean> topFive() {

    // Lookup the KeyValueStore with the provided storeName
    final ReadOnlyKeyValueStore<String, KafkaMusicExample.TopFiveSongs> topFive = streams.store
        (KafkaMusicExample.TOP_FIVE_SONGS_STORE, QueryableStoreTypes
            .<String, KafkaMusicExample.TopFiveSongs>keyValueStore());

    return topFiveSongs(KafkaMusicExample.TOP_FIVE_KEY, topFive);
  }

  private List<SongPlayCountBean> topFiveSongs(String key,
                                               final ReadOnlyKeyValueStore<String, KafkaMusicExample.TopFiveSongs> topFiveStore) {
    final ReadOnlyKeyValueStore<Long, Song> songStore = streams.store(KafkaMusicExample.ALL_SONGS,
                                                                      QueryableStoreTypes.<Long, Song>keyValueStore());
    // Get the value from the store
    final KafkaMusicExample.TopFiveSongs value = topFiveStore.get(key);
    if (value == null) {
      throw new NotFoundException();
    }
    final List<SongPlayCountBean> results = new ArrayList<>();
    value.forEach(songPlayCount -> {
      final HostStoreInfo
          host =
          metadataService.streamsMetadataForStoreAndKey(KafkaMusicExample.ALL_SONGS, songPlayCount
              .getSongId(), serializer);

      // if the song is not hosted on this instance then we need to lookup it up
      // on the instance it is on.
      if (host.getPort() != port) {
        final SongBean song =
            client.target("http://localhost:" + host.getPort() + "/kafka-music/song/" +
                          songPlayCount.getSongId())
                .request(MediaType.APPLICATION_JSON_TYPE)
                .get(SongBean.class);
        results.add(new SongPlayCountBean(song.getArtist(),song.getAlbum(), song.getName(),
                                          songPlayCount.getPlays()));
      } else {
        final Song song = songStore.get(songPlayCount.getSongId());
        results.add(new SongPlayCountBean(song.getArtist(),song.getAlbum(), song.getName(),
                                          songPlayCount.getPlays()));
      }


    });
    return results;
  }

  @GET()
  @Path("/song/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  public SongBean song(@PathParam("id") Long songId) {
    final ReadOnlyKeyValueStore<Long, Song> songStore = streams.store(KafkaMusicExample.ALL_SONGS,
                                                                      QueryableStoreTypes.<Long, Song>keyValueStore());
    final Song song = songStore.get(songId);
    if (song == null) {
      throw new NotFoundException();
    }

    return new SongBean(song.getArtist(), song.getAlbum(), song.getName());
  }

  /**
   * Get the metadata for all of the instances of this Kafka Streams application
   * @return List of {@link HostStoreInfo}
   */
  @GET()
  @Path("/instances")
  @Produces(MediaType.APPLICATION_JSON)
  public List<HostStoreInfo> streamsMetadata() {
    return metadataService.streamsMetadata();
  }

  /**
   * Get the metadata for all instances of this Kafka Streams application that currently
   * has the provided store.
   * @param store   The store to locate
   * @return  List of {@link HostStoreInfo}
   */
  @GET()
  @Path("/instances/{storeName}")
  @Produces(MediaType.APPLICATION_JSON)
  public List<HostStoreInfo> streamsMetadataForStore(@PathParam("storeName") String store) {
    return metadataService.streamsMetadataForStore(store);
  }

  /**
   * Start an embedded Jetty Server on the given port
   * @throws Exception
   */
  void start() throws Exception {
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");

    jettyServer = new Server(port);
    jettyServer.setHandler(context);

    ResourceConfig rc = new ResourceConfig();
    rc.register(this);
    rc.register(JacksonFeature.class);

    ServletContainer sc = new ServletContainer(rc);
    ServletHolder holder = new ServletHolder(sc);
    context.addServlet(holder, "/*");

    jettyServer.start();
  }

  /**
   * Stop the Jetty Server
   * @throws Exception
   */
  void stop() throws Exception {
    if (jettyServer != null) {
      jettyServer.stop();
    }
  }

}

