package coinffeine.gui.application

import scalafx.Includes._
import scalafx.beans.property.ObjectProperty
import scalafx.event.ActionEvent
import scalafx.scene.control.{Separator, ToggleButton, ToolBar}
import scalafx.scene.layout.BorderPane
import scalafx.scene.{Node, Parent, Scene}

import org.controlsfx.control.SegmentedButton

import coinffeine.gui.application.ApplicationScene._

/** Main scene of the application
  *
  * @param views  Available application views. The first one is visible at application start.
  */
class ApplicationScene(views: Seq[ApplicationView], toolbarWidgets: Seq[Node])
  extends Scene(width = DefaultWidth, height = DefaultHeight) {

  require(views.nonEmpty, "At least one view is required")

  stylesheets.add("/css/main.css")

  val currentView = new ObjectProperty[ApplicationView](this, "currentView", null)

  private val viewSelector: Parent = {
    val selector = new SegmentedButton {
      setId("view-selector")
    }
    val buttons = for (view <- views) yield new ToggleButton(view.name) {
      disable <== selected
      handleEvent(ActionEvent.ACTION) { () => currentView.value = view }
    }
    buttons.foreach(b => selector.getButtons.add(b))
    buttons.head.selected = true
    selector
  }

  private val toolbarPane = new ToolBar {
    content = Seq(viewSelector, new Separator()) ++ toolbarWidgets
  }

  root = {
    val mainPane = new BorderPane {
      top = toolbarPane
    }
    currentView.onChange { mainPane.center = currentView.value.centerPane }
    mainPane
  }

  currentView.value = views.head
}

object ApplicationScene {
  val DefaultWidth = 600
  val DefaultHeight = 400
}
