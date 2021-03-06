package com.sevenfloor.mtcsound;

import android.util.Log;

import com.sevenfloor.mtcsound.state.DeviceState;
import com.sevenfloor.mtcsound.state.EqualizerBand;
import com.sevenfloor.mtcsound.state.Input;
import com.sevenfloor.mtcsound.state.SoundProfile;

import java.util.ArrayList;
import java.util.List;

public class HwInterface {

    private final Register R01 = new Register(0x01, 0b10100100);
    private final Register R02 = new Register(0x02, 0b00000000);
    private final Register R03 = new Register(0x03, 0b00000001);
    private final Register R05 = new Register(0x05, 0b10000000);
    private final Register R06 = new Register(0x06, 0b00000000);
    private final Register R20 = new Register(0x20, 0b11111111);
    private final Register R28 = new Register(0x28, 0b11111111);
    private final Register R29 = new Register(0x29, 0b11111111);
    private final Register R2A = new Register(0x2A, 0b11111111);
    private final Register R2B = new Register(0x2B, 0b11111111);
    private final Register R2C = new Register(0x2C, 0b11111111);
    private final Register R30 = new Register(0x30, 0b11111111);
    private final Register R41 = new Register(0x41, 0b00000000);
    private final Register R44 = new Register(0x44, 0b00000000);
    private final Register R47 = new Register(0x47, 0b00000000);
    private final Register R51 = new Register(0x51, 0b10000000);
    private final Register R54 = new Register(0x54, 0b10000000);
    private final Register R57 = new Register(0x57, 0b10000000);
    private final Register R75 = new Register(0x75, 0b00000000);

    private final Register AdvancedSwitch = R01;
    private final Register SubwooferSetup = R02;
    private final Register LoudnessFrequency = R03;
    private final Register InputSelector = R05;
    private final Register InputGain = R06;
    private final Register VolumeGain = R20;
    private final Register FaderFrontRight = R28;
    private final Register FaderFrontLeft = R29;
    private final Register FaderRearRight = R2A;
    private final Register FaderRearLeft = R2B;
    private final Register FaderSubwoofer = R2C;
    private final Register MixingGain = R30;
    private final Register EqBassSetup = R41;
    private final Register EqMiddleSetup = R44;
    private final Register EqTrebleSetup = R47;
    private final Register EqBassGain = R51;
    private final Register EqMiddleGain = R54;
    private final Register EqTrebleGain = R57;
    private final Register LoudnessGainHiCut = R75;

    private final Register[] AllRegisters = new Register[] { R01, R02, R03, R05, R06, R20, R28, R29, R2A, R2B, R2C, R30, R41, R44, R47, R51, R54, R57, R75 };

    private List<String> fileNames;
    private String fileName = null;
    private String stateMessage;

    public HwInterface(List<String> fileNames) {
        this.fileNames = fileNames;
        check();
    }

    public boolean isOnline() {
        return fileName != null;
    }

    public String getStateDescription() {
        if (fileName != null)
            return "Controlling via " + fileName;
        return stateMessage;
    }

    public void check() {
        if (fileName != null) return;
        Log.i(Utils.logTag, "Checking hardware");
        stateMessage = "No accessible device files";
        fileName = null;
        for (String fn: fileNames) {
            stateMessage = I2cBus.write(fn, 0x40,new byte[][]{{0x01}});
            if (stateMessage == null) {
                Log.i(Utils.logTag, String.format("Checking %s - Success", fn));
                fileName = fn;
                Log.i(Utils.logTag, getStateDescription());
                return;
            } else {
                Log.e(Utils.logTag, String.format("Checking %s - %s", fn, stateMessage));
            }
        }
        stateMessage = "Error checking hardware: " + stateMessage;
        Log.i(Utils.logTag, getStateDescription());
    }

    public void applyState(DeviceState state, boolean forced) {
        if (fileName == null) {
            check();
            if (fileName == null) return;
            forced = true; // if connected for the first time
        }

        applySettings(state);

        applyMixingGain(state);
        applyVolume(state);
        applyBalanceAndFader(state);

        SoundProfile profile = state.getCurrentProfile();
        applyInputGain(profile);
        applyEqualizerBand(profile.equalizerOn, profile.bassBand, EqBassSetup, EqBassGain);
        applyEqualizerBand(profile.equalizerOn, profile.middleBand, EqMiddleSetup, EqMiddleGain);
        applyEqualizerBand(profile.equalizerOn, profile.trebleBand, EqTrebleSetup, EqTrebleGain);
        applyLoudness(profile.loudnessOn, profile);

        applyInput(state);
        applyMute(state);

        //writeRegistersToI2C(forced);
        writeAllRegistersToI2C();
    }

    private void applySettings(DeviceState state) {
        AdvancedSwitch.value = 0b10110111; // hard coded to maximum switch times
        int subCut = state.settings.subwoofer.getCutFrequency();
        int subOut = state.settings.subwoofer.getOutput();
        int subPhase= state.settings.subwoofer.getPhase();
        SubwooferSetup.value = (subPhase << 7) | (subOut << 4) | (subCut);
        int db = state.settings.subwoofer.getGain();
        FaderSubwoofer.value = 128 - db;
    }

    private void applyMixingGain(DeviceState state) {
        // don't mix with the phone
        if (state.isPhone()) {
            MixingGain.value = 0xFF;
            return;
        }
        // don't mix with itself or gsm_bt (it's the same hw input)
        if (state.inputMode.input == Input.sys || state.inputMode.input == Input.gsm_bt) {
            MixingGain.value = 0xFF;
            return;
        }
        int vol = state.volume.getValueInDb();
        int sysGain = state.sysProfile.getInputGain();
        int cut = state.backViewState.getActualCut();
        int result = vol + sysGain - cut;
        if (result > 7) result = 7;
        MixingGain.value = result < -79 ? 0xFF : 128 - result;
    }

    private void applyInput(DeviceState state) {
        if (state.isPhone()) {
            // some types of BT (e.g. BC6B) use system always
            if (state.settings.gsmAltInput)
                InputSelector.value = 0x81;
            else // call in progress or outgoing connection tone using bluetooth input
                InputSelector.value = 0x80;

            // mute channels that are disabled in settings
            if (!state.settings.phoneOut.fl)
                FaderFrontLeft.value = 0xFF;
            if (!state.settings.phoneOut.fr)
                FaderFrontRight.value = 0xFF;
            if (!state.settings.phoneOut.rl)
                FaderRearLeft.value = 0xFF;
            if (!state.settings.phoneOut.rr)
                FaderRearRight.value = 0xFF;

            MixingGain.value = 0xFF;
            return;
        }

        switch (state.inputMode.input)
        {
            case sys:
            case gsm_bt:
                InputSelector.value = 0x81;
                break;
            case dtv:
            case dvd:
                InputSelector.value = 0x82;
                break;
            case dvr: // ? need to check with the logic analyzer
            case line:
                InputSelector.value = 0x83;
                break;
            case fm:
                InputSelector.value = 0x8A;
                break;
            case ipod:
                InputSelector.value = 0x8B;
                break;
        }
    }

    private void applyInputGain(SoundProfile profile){
        InputGain.value = profile.getInputGain();
    }

    private void applyVolume(DeviceState state) {
        int db = state.getCurrentVolume().getValueInDb();

        int cut = 0;

        // cut only if no phone operations in progress
        if (!state.isPhone()) {
            cut = state.backViewState.getActualCut();
            // cut for gps only when the inputs are not sys nor gsm_bt since they're already cut by stock native code
            if (state.inputMode.input != Input.sys && state.inputMode.input != Input.gsm_bt)
                cut = cut + state.gpsState.getActualCut();
        }

        int result = db - cut;
        if (result > 15) result = 15;
        VolumeGain.value = result < -79 ? 0xFF : 128 - result;
    }

    private void applyBalanceAndFader(DeviceState state){
        int dbfl = 0, dbfr = 0, dbrl = 0, dbrr = 0, value;

        value = state.balance.getValue();
        if (value != 0) {
            int db = state.balance.getAttenuationInDB();
            if (value > 0) {
                dbfl -= db;
                dbrl -= db;
            } else {
                dbfr -= db;
                dbrr -= db;
            }
        }

        value = state.fader.getValue();
        if (value != 0) {
            int db = state.fader.getAttenuationInDB();
            if (value > 0) {
                dbrl -= db;
                dbrr -= db;
            } else {
                dbfl -= db;
                dbfr -= db;
            }
        }

        FaderFrontLeft.value = 128 - dbfl;
        FaderFrontRight.value = 128 - dbfr;
        FaderRearLeft.value = 128 - dbrl;
        FaderRearRight.value = 128 - dbrr;
    }

    private void applyEqualizerBand(boolean on, EqualizerBand band, Register rSetup, Register rGain) {
        int g = 0, f = 0, q = 0;
        if (on) {
            g = band.getGain();
            f = band.getFrequency();
            q = band.getQuality();
        }
        rSetup.value &= 0b11001100;
        rSetup.value |= (q) | (f << 4);
        if (g < 0) g = (-g) | 0b10000000;
        rGain.value = g;
    }

    private void applyLoudness(boolean on, SoundProfile profile) {
        int g = 0, f = 0, c = 0;
        if (on) {
            g = profile.loudness.getGain();
            f = profile.loudness.getFrequency();
            c = profile.loudness.getHicut();
        }
        LoudnessFrequency.value &= 0b11100111;
        LoudnessFrequency.value |= (f << 3);
        LoudnessGainHiCut.value &= 0b10000000;
        LoudnessGainHiCut.value |= g | (c << 5);
    }

    private void applyMute(DeviceState state) {
        if (state.mute || state.getCurrentVolume().getValue() == 0 || (state.settings.recMute && state.recActive)) {
            InputGain.value = InputGain.value | (1 << 7);
            VolumeGain.value = 0xFF;
            FaderFrontRight.value = 0xFF;
            FaderFrontLeft.value = 0xFF;
            FaderRearRight.value = 0xFF;
            FaderRearLeft.value = 0xFF;
            FaderSubwoofer.value = 0xFF;
            MixingGain.value = 0xFF;
        }
    }

    private void writeRegistersToI2C(boolean forced) {
        // implements write of a single register at a time for each of changed registers
        // i.e. 0x80 0x01 [0x01] 0x80 0x02 [0x02] 0x80 0x03 [0x03] etc.
        ArrayList<byte[]> buffer = new ArrayList<>(AllRegisters.length);
        for (Register r: AllRegisters) {
            if (forced || r.isChanged()) {
                buffer.add(new byte[]{(byte) r.index, (byte) r.value});
                r.flush();
            }
        }
        I2cBus.write(fileName, 0x40, buffer.toArray(new byte[][]{}));
    }

    private void writeAllRegistersToI2C() {
        // implements a single continuous write of all registers
        // i.e. 0x80 0x01 [0x01] [0x02] [0x03] [0x05] [0x06] [0x20] ... [0x57] [0x75]
        byte[] buffer = new byte[AllRegisters.length + 1];
        buffer[0] = (byte) AllRegisters[0].index;
        for (int i = 0; i < AllRegisters.length; i++) {
            Register r = AllRegisters[i];
            buffer[i + 1] = (byte) r.value;
            r.flush();
        }
        I2cBus.write(fileName, 0x40, new byte[][]{buffer});
    }

    private class Register {
        int index;
        int lastValue;
        int value;

        public Register(int index, int defaultValue){
            this.index = index;
            this.value = defaultValue;
            this.lastValue = -1; // so that first time it will be written forcibly
        }

        public boolean isChanged() {
            return lastValue != value;
        }

        public void flush(){
            lastValue = value;
        }
    }

}

