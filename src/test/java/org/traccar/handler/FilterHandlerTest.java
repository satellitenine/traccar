package org.traccar.handler;

import org.junit.Before;
import org.junit.Test;
import org.traccar.BaseTest;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.DataManager;
import org.traccar.database.IdentityManager;
import org.traccar.model.Device;
import org.traccar.model.Position;

import java.util.Date;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FilterHandlerTest extends BaseTest {

    private FilterHandler passingHandler;
    private FilterHandler filteringHandler;

    @Before
    public void passingHandler() {
        var config = mock(Config.class);
        when(config.getBoolean(Keys.FILTER_ENABLE)).thenReturn(true);
        var identityManager = mock(IdentityManager.class);
        var dataManager = mock(DataManager.class);
        passingHandler = new FilterHandler(config, identityManager, dataManager);
    }

    @Before
    public void filteringHandler() {
        var config = mock(Config.class);
        when(config.getBoolean(Keys.FILTER_ENABLE)).thenReturn(true);
        when(config.getBoolean(Keys.FILTER_INVALID)).thenReturn(true);
        when(config.getBoolean(Keys.FILTER_ZERO)).thenReturn(true);
        when(config.getBoolean(Keys.FILTER_DUPLICATE)).thenReturn(true);
        when(config.getLong(Keys.FILTER_FUTURE)).thenReturn(5 * 60L);
        when(config.getBoolean(Keys.FILTER_APPROXIMATE)).thenReturn(true);
        when(config.getBoolean(Keys.FILTER_STATIC)).thenReturn(true);
        when(config.getInteger(Keys.FILTER_DISTANCE)).thenReturn(10);
        when(config.getInteger(Keys.FILTER_MAX_SPEED)).thenReturn(500);
        when(config.getLong(Keys.FILTER_SKIP_LIMIT)).thenReturn(10L);
        when(config.getBoolean(Keys.FILTER_SKIP_ATTRIBUTES_ENABLE)).thenReturn(true);
        var identityManager = mock(IdentityManager.class);
        when(identityManager.lookupAttributeString(0, "filter.skipAttributes", "", false, true)).thenReturn("alarm,result");
        when(identityManager.getById(0)).thenReturn(mock(Device.class));
        var dataManager = mock(DataManager.class);
        filteringHandler = new FilterHandler(config, identityManager, dataManager);
    }

    private Position createPosition(Date time, boolean valid, double speed) {
        Position position = new Position();
        position.setDeviceId(0);
        position.setTime(time);
        position.setValid(valid);
        position.setLatitude(10);
        position.setLongitude(10);
        position.setAltitude(10);
        position.setSpeed(speed);
        position.setCourse(10);
        return position;
    }

    @Test
    public void testFilter() {

        Position position = createPosition(new Date(), true, 10);

        assertNotNull(filteringHandler.handlePosition(position));
        assertNotNull(passingHandler.handlePosition(position));

        position = createPosition(new Date(Long.MAX_VALUE), true, 10);

        assertNull(filteringHandler.handlePosition(position));
        assertNotNull(passingHandler.handlePosition(position));

        position = createPosition(new Date(), false, 10);

        assertNull(filteringHandler.handlePosition(position));
        assertNotNull(passingHandler.handlePosition(position));

    }

    @Test
    public void testSkipAttributes() {

        Position position = createPosition(new Date(), true, 0);
        position.set(Position.KEY_ALARM, Position.ALARM_GENERAL);

        assertNotNull(filteringHandler.handlePosition(position));

    }

}
