package net.floodlightcontroller.fastfailoverdemo.web;

import net.floodlightcontroller.restserver.RestletRoutable;

import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;

/**
 * This class is what we give to the IRestApiService. It will
 * provide the service with the URIs that we implement so that
 * when an HTTP request is received, the IRestApiService can
 * associate URIs we're interested in with the handlers we 
 * indicate.
 * 
 * For example, we give the TogglePathResource class as the handler
 * for any requests that are for the URI /toggle-path.
 * 
 * @author Ryan Izard, rizard@g.clemson.edu, ryan.izard@bigswitch.com
 *
 */
public class FastFailoverDemoRoutable implements RestletRoutable {

	@Override
	public Restlet getRestlet(Context context) {
		/*
		 * Here, we register the various APIs our module implements.
		 * An HTTP request with any of these URIs will be handled by
		 * the provided class.
		 */
		Router router = new Router(context);
		router.attach("/toggle-path", TogglePathResource.class);
		router.attach("/reset", ResetResource.class);
		return router;
	}

	@Override
	public String basePath() {
		/*
		 * This is the URI base our module will cover.
		 */
		return "/wm/fast-failover-demo";
	}

}
