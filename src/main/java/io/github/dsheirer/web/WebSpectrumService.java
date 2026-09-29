/*
 * ****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.web;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.spectrum.ComplexDftProcessor;
import io.github.dsheirer.spectrum.DFTSize;
import io.github.dsheirer.spectrum.converter.ComplexDecibelConverter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Supplies a lightweight FFT snapshot for the browser spectrum and waterfall display. */
public class WebSpectrumService
{
    private final ComplexDftProcessor<INativeBuffer> mProcessor = new ComplexDftProcessor<>();
    private final ComplexDecibelConverter mConverter = new ComplexDecibelConverter();
    private volatile float[] mBins = new float[0];
    private volatile long mTimestamp;
    private DiscoveredTuner mSelected;

    public WebSpectrumService()
    {
        mProcessor.setDFTSize(DFTSize.FFT01024);
        mConverter.addListener(results ->
        {
            mBins = results.clone();
            mTimestamp = System.currentTimeMillis();
        });
        mProcessor.addConverter(mConverter);
        mProcessor.stop();
    }

    public synchronized Map<String,Object> snapshot(TunerManager tunerManager, String tunerId)
    {
        DiscoveredTuner requested = null;
        for(DiscoveredTuner discovered: new ArrayList<>(tunerManager.getAvailableTuners()))
        {
            if(discovered.hasTuner() && (requested == null || discovered.getId().equals(tunerId)))
            {
                requested = discovered;
                if(discovered.getId().equals(tunerId)) { break; }
            }
        }

        if(requested == null)
        {
            detach();
            return Map.of("available", false, "bins", new float[0]);
        }

        if(requested != mSelected)
        {
            detach();
            mSelected = requested;
            mBins = new float[0];
            mProcessor.clearBuffer();
            mProcessor.start();
            requested.getTuner().getTunerController().addBufferListener(mProcessor);
        }

        Tuner tuner = requested.getTuner();
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("available", true);
        value.put("id", requested.getId());
        value.put("name", tuner.getPreferredName());
        value.put("frequency", tuner.getTunerController().getFrequency());
        value.put("sampleRate", tuner.getTunerController().getSampleRate());
        value.put("timestamp", mTimestamp);
        value.put("bins", mBins);
        return value;
    }

    public synchronized void dispose()
    {
        detach();
        mConverter.dispose();
        mProcessor.dispose();
    }

    private void detach()
    {
        if(mSelected != null && mSelected.hasTuner())
        {
            mSelected.getTuner().getTunerController().removeBufferListener(mProcessor);
        }
        mSelected = null;
        mProcessor.stop();
        mProcessor.clearBuffer();
    }
}
