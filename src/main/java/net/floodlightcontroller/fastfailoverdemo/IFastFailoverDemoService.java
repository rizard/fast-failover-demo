package net.floodlightcontroller.fastfailoverdemo;

import java.util.Map;
import net.floodlightcontroller.core.module.IFloodlightService;

/**
 * This is the IFloodlightService that we expose to other modules
 * in the controller. For the purposes of this demo, we really don't
 * want other user modules to tap in and use our service. But, the
 * IRestApiService needs a way to hook into our module when it receives
 * an HTTP request destined for us. The IRestApiService has access to
 * all IFloodlightServices (including us). As such, it can call any
 * functions exposed through our interface that extends IFloodlightService.
 * 
 * The FastFailoverDemo class implements this service. So, the
 * handleToggleRequest() function there can be called from our URI handler,
 * TogglePathResource in the web package.
 * 
 * @author Ryan Izard, rizard@g.clemson.edu, ryan.izard@bigswitch.com
 *
 */
public interface IFastFailoverDemoService extends IFloodlightService {
	Map<String, String> handleToggleRequest(String json);
	
	Map<String, String> handleResetRequest(String json);
}
