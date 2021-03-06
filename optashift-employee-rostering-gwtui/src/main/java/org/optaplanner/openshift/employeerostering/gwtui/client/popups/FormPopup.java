package org.optaplanner.openshift.employeerostering.gwtui.client.popups;

import com.google.gwt.dom.client.Style.Position;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.PopupPanel;
import elemental2.dom.HTMLElement;
import jsinterop.base.Js;
import org.gwtbootstrap3.client.ui.html.Div;
import org.jboss.errai.ui.client.local.api.elemental2.IsElement;
import org.optaplanner.openshift.employeerostering.gwtui.client.resources.css.CssResources;

public class FormPopup extends PopupPanel {

    protected FormPopup(IsElement content) {
        super(false);

        setStyleName(getStyles().panel());
        setGlassStyleName(getStyles().glass());
        setGlassEnabled(true);

        Div container = new Div();
        container.getElement().appendChild(Js.cast(content.getElement()));
        setWidget(container);
    }

    public void showFor(IsElement isElement) {
        final HTMLElement element = isElement.getElement();
        setPopupPositionAndShow((w, h) -> {
            final Integer offsetLeft = (int) (Window.getScrollLeft() + Math.round(getOffsetRight(element, w)));
            final Integer offsetTop = (int) (Window.getScrollTop() + Math.round(getOffsetTop(element, h)));
            setPopupPosition(offsetLeft, offsetTop);
        });
    }

    public void center(final int width, final int height) {
        this.getContainerElement().getStyle().setPosition(Position.FIXED);
        this.setPopupPositionAndShow((w, h) -> {
            this.setPopupPosition(Window.getClientWidth() / 2 - width / 2,
                                  Window.getClientHeight() / 2 - height / 2);
        });

    }

    private double getOffsetRight(HTMLElement element, int w) {
        return Math.max(0, Math.min(Window.getClientWidth() - w, element.getBoundingClientRect().left + element.scrollWidth));
    }

    private double getOffsetTop(HTMLElement element, int h) {
        return Math.max(0, Math.min(Window.getClientHeight() - h, element.getBoundingClientRect().top));
    }

    @Override
    public void hide() {
        super.hide();
    }

    public static CssResources.PopupCss getStyles() {
        CssResources.INSTANCE.popup().ensureInjected();
        return CssResources.INSTANCE.popup();
    }

}
