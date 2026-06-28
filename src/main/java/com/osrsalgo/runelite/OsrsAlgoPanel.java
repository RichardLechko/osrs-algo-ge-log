package com.osrsalgo.runelite;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.PluginPanel;

/** Minimal status panel — one dot + one-liner. Updated via
 *  setStatus() / setLastEvent() from the plugin thread; Swing methods
 *  bounce to the EDT internally. */
public class OsrsAlgoPanel extends PluginPanel
{
    private final JLabel statusDot   = new JLabel("●");
    private final JLabel statusText  = new JLabel("starting up");
    private final JLabel lastEvent   = new JLabel(" ");

    public OsrsAlgoPanel()
    {
        setBorder(new EmptyBorder(10, 10, 10, 10));
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        statusDot.setFont(statusDot.getFont().deriveFont(24f));
        statusDot.setForeground(Color.GRAY);
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        head.add(statusDot); head.add(statusText);
        add(head);
        lastEvent.setFont(lastEvent.getFont().deriveFont(11f));
        add(lastEvent);
    }

    public void setStatus(String label, Color color)
    {
        SwingUtilities.invokeLater(() -> {
            statusDot.setForeground(color);
            statusText.setText(label);
        });
    }

    public void setLastEvent(String line)
    {
        SwingUtilities.invokeLater(() -> lastEvent.setText(line));
    }

}
