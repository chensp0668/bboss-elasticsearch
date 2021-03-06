package org.frameworkset.elasticsearch.client;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.frameworkset.elasticsearch.ElasticSearch;
import org.frameworkset.spi.BaseApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动发现es 主机节点
 */
public class HostDiscover extends Thread{
	private final JsonFactory jsonFactory;
	private static Logger logger = LoggerFactory.getLogger(HostDiscover.class);
	private Scheme scheme = HostDiscover.Scheme.HTTP;
	private long discoverInterval  = 10000l;
	private ClientInterface clientInterface ;
	private ElasticSearch elasticSearch;
	private ElasticSearchRestClient elasticSearchRestClient;
	public HostDiscover(ElasticSearchRestClient elasticSearchRestClient ){
		super("ElasticSearch HostDiscover Thread");
		this.jsonFactory = new JsonFactory();
		this.elasticSearchRestClient = elasticSearchRestClient;
		this.elasticSearch = elasticSearchRestClient.getElasticSearch();
		this.clientInterface = elasticSearch.getRestClientUtil();
		BaseApplicationContext.addShutdownHook(new Runnable() {
			@Override
			public void run() {
				stopCheck();
			}
		});
	}
	boolean stop = false;
	public void stopCheck(){
		this.stop = true;
		this.interrupt();
	}

	private void handleDiscoverHosts(List<HttpHost> hosts){
		List<ESAddress> newAddress = new ArrayList<ESAddress>();
		//恢复移除节点
		elasticSearchRestClient.recoverRemovedNodes(hosts);
		//识别新增节点
		for(int i = 0; i < hosts.size();i ++){
			ESAddress address = new ESAddress(hosts.get(i).toString());
			if(!elasticSearchRestClient.containAddress(address)){
				newAddress.add(address);
			}
		}
		//处理新增节点
		if(newAddress.size() > 0) {
			if (logger.isInfoEnabled()) {
				logger.info(new StringBuilder().append("Discovery new elasticsearch server[").append(newAddress).append("].").toString());
			}
			elasticSearchRestClient.addAddresses(newAddress);
		}
		//处理删除节点
		elasticSearchRestClient.handleRemoved( hosts);
	}
	@Override
	public void run() {
		do {
			if(this.stop)
				break;
			try {
				clientInterface.discover("_nodes/http",ClientInterface.HTTP_GET, new ResponseHandler<Void>() {

					@Override
					public Void handleResponse(HttpResponse response) throws ClientProtocolException, IOException {
						int status = response.getStatusLine().getStatusCode();
						if (status >= 200 && status < 300) {
							List<HttpHost> hosts = readHosts(response.getEntity());
							handleDiscoverHosts(hosts);

						} else {

						}
						return null;
					}
				});

			} catch (Exception e) {
				if (logger.isInfoEnabled())
					logger.info("Discovery elasticsearch server failed:",e);
			}
			try {
				sleep(discoverInterval);
			} catch (InterruptedException e) {
				break;
			}
		}while(true);

	}

	private List<HttpHost> readHosts(HttpEntity entity) throws IOException {
		InputStream inputStream = entity.getContent();
		Throwable var3 = null;

		try {
			JsonParser parser = this.jsonFactory.createParser(inputStream);
			if (parser.nextToken() != JsonToken.START_OBJECT) {
				throw new IOException("expected data to start with an object");
			} else {
				ArrayList hosts = new ArrayList();

				while(true) {
					while(true) {
						do {
							if (parser.nextToken() == JsonToken.END_OBJECT) {
								ArrayList var18 = hosts;
								return var18;
							}
						} while(parser.getCurrentToken() != JsonToken.START_OBJECT);

						if ("nodes".equals(parser.getCurrentName())) {
							while(parser.nextToken() != JsonToken.END_OBJECT) {
								JsonToken token = parser.nextToken();

								assert token == JsonToken.START_OBJECT;

								String nodeId = parser.getCurrentName();
								HttpHost sniffedHost = readHost(nodeId, parser, this.scheme);
								if (sniffedHost != null) {
									logger.trace("adding node [" + nodeId + "]");
									hosts.add(sniffedHost);
								}
							}
						} else {
							parser.skipChildren();
						}
					}
				}
			}
		} catch (IOException var16) {
			var3 = var16;
			throw var16;
		} finally {
			if (inputStream != null) {
				if (var3 != null) {
					try {
						inputStream.close();
					} catch (Throwable var15) {
						var3.addSuppressed(var15);
					}
				} else {
					inputStream.close();
				}
			}

		}
	}

	private static HttpHost readHost(String nodeId, JsonParser parser, Scheme scheme) throws IOException {
		HttpHost httpHost = null;
		String fieldName = null;

		while(true) {
			label41:
			while(parser.nextToken() != JsonToken.END_OBJECT) {
				if (parser.getCurrentToken() == JsonToken.FIELD_NAME) {
					fieldName = parser.getCurrentName();
				} else if (parser.getCurrentToken() == JsonToken.START_OBJECT) {
					if (!"http".equals(fieldName)) {
						parser.skipChildren();
					} else {
						while(true) {
							while(true) {
								if (parser.nextToken() == JsonToken.END_OBJECT) {
									continue label41;
								}

								if (parser.getCurrentToken() == JsonToken.VALUE_STRING && "publish_address".equals(parser.getCurrentName())) {
									URI boundAddressAsURI = URI.create(scheme + "://" + parser.getValueAsString());
									httpHost = new HttpHost(boundAddressAsURI.getHost(), boundAddressAsURI.getPort(), boundAddressAsURI.getScheme());
								} else if (parser.getCurrentToken() == JsonToken.START_OBJECT) {
									parser.skipChildren();
								}
							}
						}
					}
				}
			}

			if (httpHost == null) {
				logger.debug("skipping node [" + nodeId + "] with http disabled");
				return null;
			}

			return httpHost;
		}
	}



	public static enum Scheme {
		HTTP("http"),
		HTTPS("https");

		private final String name;

		private Scheme(String name) {
			this.name = name;
		}

		public String toString() {
			return this.name;
		}
	}
}
