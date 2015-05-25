package net.floodlightcontroller.fastfailoverdemo.web;

import java.util.Map;

import net.floodlightcontroller.fastfailoverdemo.IFastFailoverDemoService;

import org.restlet.resource.Post;
import org.restlet.resource.Put;
import org.restlet.resource.ServerResource;

/**
 * Handle any relevant HTTP requests that are sent to the URI we're
 * registered as. Then, hook into the IFastFailoverDemoService to handle
 * the request within our module.
 * 
 * @author Ryan Izard, rizard@g.clemson.edu, ryan.izard@bigswitch.com
 *
 */
public class ResetResource extends ServerResource {
	
	/**
	 * This function is pretty simple. If it has been called,
	 * then the REST API has matched the URI to the one our
	 * class TogglePathResource is registered for. The annotations
	 * are what links the HTTP request type to this function. You
	 * can have multiple callback functions for different types of
	 * HTTP requests (e.g. most commonly GET, PUT, POST, and DELETE).
	 * 
	 * @param json, The payload of the HTTP request. In Floodlight,
	 * we typically use JSON for ease-of-use. Thus, I call the String
	 * "json."
	 * @return A JSON-formatted key:value String denoting the status
	 * of the operation that was performed.
	 */
	@Post
	@Put
	public Map<String, String> handleRequest(String json) {
		return ((IFastFailoverDemoService) getContext().getAttributes().get(IFastFailoverDemoService.class.getCanonicalName())).handleResetRequest(json);
	}
}
