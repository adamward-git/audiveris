//----------------------------------------------------------------------------//
//                                                                            //
//                        G l y p h S i g n a t u r e                         //
//                                                                            //
//  Copyright (C) Herve Bitteur 2000-2007. All rights reserved.               //
//  This software is released under the GNU General Public License.           //
//  Contact author at herve.bitteur@laposte.net to report bugs & suggestions. //
//----------------------------------------------------------------------------//
//
package omr.glyph;

import omr.util.Implement;
import omr.util.RectangleFacade;

import java.awt.Rectangle;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Class <code>GlyphSignature</code> is used to implement a map of glyphs,
 * based only on their physical properties.
 *
 * @author Herv&eacute Bitteur
 * @version $Id$
 */
@XmlAccessorType(XmlAccessType.NONE)
@XmlRootElement(name = "glyph-signature")
public class GlyphSignature
    implements Comparable<GlyphSignature>
{
    //~ Instance fields --------------------------------------------------------

    /** Glyph weight */
    @XmlElement
    private final int weight;

    /** Glyph contour box */
    private Rectangle contourBox;

    //~ Constructors -----------------------------------------------------------

    //----------------//
    // GlyphSignature //
    //----------------//
    /**
     * Creates a new GlyphSignature object.
     *
     * @param glyph the glyph to compute signature upon
     */
    public GlyphSignature (Glyph glyph)
    {
        weight = glyph.getWeight();
        contourBox = glyph.getContourBox();
    }

    /**
     * Just for debugging, to be able to forge a signature
     *
     * @param weight
     * @param contourBox
     */
    public GlyphSignature (int       weight,
                           Rectangle contourBox)
    {
        this.weight = weight;
        this.contourBox = contourBox;
    }

    //----------------//
    // GlyphSignature //
    //----------------//
    /**
     * Needed by JAXB
     */
    private GlyphSignature ()
    {
        weight = 0;
        contourBox = null;
    }

    //~ Methods ----------------------------------------------------------------

    //-----------//
    // compareTo //
    //-----------//
    @Implement(Comparable.class)
    public int compareTo (GlyphSignature other)
    {
        if (weight < other.weight) {
            return -1;
        } else if (weight > other.weight) {
            return 1;
        }

        if (contourBox.x < other.contourBox.x) {
            return -1;
        } else if (contourBox.x > other.contourBox.x) {
            return 1;
        }

        if (contourBox.y < other.contourBox.y) {
            return -1;
        } else if (contourBox.y > other.contourBox.y) {
            return 1;
        }

        if (contourBox.width < other.contourBox.width) {
            return -1;
        } else if (contourBox.width > other.contourBox.width) {
            return 1;
        }

        if (contourBox.height < other.contourBox.height) {
            return -1;
        } else if (contourBox.height > other.contourBox.height) {
            return 1;
        }

        return 0; // Equal
    }

    @Override
    public boolean equals (Object obj)
    {
        if (obj == this) {
            return true;
        }

        if (obj instanceof GlyphSignature) {
            GlyphSignature that = (GlyphSignature) obj;

            return (weight == that.weight) &&
                   (contourBox.x == that.contourBox.x) &&
                   (contourBox.y == that.contourBox.y) &&
                   (contourBox.width == that.contourBox.width) &&
                   (contourBox.height == that.contourBox.height);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode ()
    {
        int hash = 7;
        hash = (41 * hash) + this.weight;

        return hash;
    }

    //    @Override
    //    public int hashCode ()
    //    {
    //        int hash = 5;
    //        hash = (11 * hash) + this.weight;
    //        hash = (11 * hash) +
    //               ((this.contourBox != null) ? this.contourBox.hashCode() : 0);
    //
    //        return hash;
    //    }

    //----------//
    // toString //
    //----------//
    @Override
    public String toString ()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("{Sig ")
          .append("weight=")
          .append(weight)
          .append(" Rectangle[x=")
          .append(contourBox.x)
          .append(",y=")
          .append(contourBox.y)
          .append(",width=")
          .append(contourBox.width)
          .append(",height=")
          .append(contourBox.height)
          .append("}");

        return sb.toString();
    }

    //------------------//
    // setXmlContourBox //
    //------------------//
    @XmlElement(name = "contour-box")
    private void setXmlContourBox (RectangleFacade xr)
    {
        contourBox = xr.getRectangle();
    }

    //------------------//
    // getXmlContourBox //
    //------------------//
    private RectangleFacade getXmlContourBox ()
    {
        if (contourBox != null) {
            return new RectangleFacade(contourBox);
        } else {
            return null;
        }
    }
}
