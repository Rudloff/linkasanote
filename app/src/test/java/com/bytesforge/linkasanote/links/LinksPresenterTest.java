package com.bytesforge.linkasanote.links;

import org.junit.Before;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Mockito.when;

public class LinksPresenterTest {

    @Mock
    private LinksContract.View linksView;

    private LinksPresenter linksPresenter;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        linksPresenter = new LinksPresenter(linksView);

        when(linksView.isActive()).thenReturn(true);
    }
}