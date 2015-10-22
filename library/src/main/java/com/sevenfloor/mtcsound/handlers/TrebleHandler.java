package com.sevenfloor.mtcsound.handlers;

import com.sevenfloor.mtcsound.Device;
import com.sevenfloor.mtcsound.state.EqualizerBand;

public class TrebleHandler extends EqualizerBandHandler {
    public TrebleHandler(Device device) {
        super(device);
    }

    @Override
    protected EqualizerBand getBand() {
        return device.state.getCurrentProfile().trebleBand;
    }
}