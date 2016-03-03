package com.hanhuy.android.irc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.app.AppCompatActivity
import android.view._
import android.widget._
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.common._
import com.hanhuy.android.extensions._
import iota._
import iota.pure.PureFragmentCompat
import Tweaks._
import ViewGroup.LayoutParams._

import scala.util.Try

/**
  * @author pfnguyen
  */
object ServerSetupActivity {
  val EXTRA_SERVER = "qicr.extra.SERVER_ID"
  def start(activity: Activity, server: Option[Server] = None): Unit = {
    val intent = new Intent(activity, classOf[ServerSetupActivity])
    server.foreach(s => intent.putExtra(EXTRA_SERVER, s.id))
    activity.startActivity(intent)
  }
}
class ServerSetupActivity extends AppCompatActivity {

  override def onCreate(savedInstanceState: Bundle) = {
    setTheme(if (Settings.get(Settings.DAYNIGHT_MODE)) R.style.SetupTheme_Light else R.style.SetupTheme_Dark)
    super.onCreate(savedInstanceState)
    val toolbar = newToolbar
    toolbar.setNavigationIcon(Application.getDrawable(this, resolveAttr(R.attr.qicrCloseIcon, _.resourceId)))
    setSupportActionBar(toolbar)
    val content = c[ViewGroup](l[FrameLayout](
      toolbar.! >>= lp(MATCH_PARENT, actionBarHeight, Gravity.TOP) >>= padding(all = 0),
      w[FrameLayout] >>= id(Id.content) >>= lpK(MATCH_PARENT, MATCH_PARENT)(margins(top = actionBarHeight))
    ) >>= id(android.R.id.content) >>=
      lp(MATCH_PARENT, MATCH_PARENT))
    setContentView(content.perform())
    val fm = getSupportFragmentManager
    if (fm.findFragmentByTag(MainActivity.SERVER_SETUP_FRAGMENT) == null) {
      val fragment = new ServerSetupFragment
      val id = getIntent.getLongExtra(ServerSetupActivity.EXTRA_SERVER, -1)
      if (id != -1) {
        val b = new Bundle(getIntent.getExtras)
        fragment.setArguments(b)
        Config.servers.now.find(_.id == id).foreach(s => getSupportActionBar.setTitle("Edit: " + s.name))
      } else {
        getSupportActionBar.setTitle("Add a Server")
      }
      fm.beginTransaction
        .add(Id.content, fragment, MainActivity.SERVER_SETUP_FRAGMENT)
        .commit()
    }
  }
}

object ServerSetupFragment {
  val EXTRA_MODEL = "qicr.edit.SERVER_MODEL"
  object ServerEditModel {
    def fromData(data: Server.ServerData) = ServerEditModel(
      data.id,
      Some(data.name),
      Some(data.hostname),
      Some(data.nickname),
      Some(data.altnick),
      data.port,
      Some(data.realname),
      Some(data.username),
      data.ssl,
      data.autoconnect,
      data.password,
      data.sasl,
      data.autorun,
      data.autojoin
    )

    def empty = ServerEditModel(-1, None, None, None, None, 6667,
      Some("stronger, better, qicr"), Some("qicruser"),
      false, false, None, false, None, None)
  }
  case class ServerEditModel(id: Long,
                             name: Option[String],
                             hostname: Option[String],
                             nickname: Option[String],
                             altnick: Option[String],
                             port: Int,
                             realname: Option[String],
                             username: Option[String],
                             ssl: Boolean,
                             autoconnect: Boolean,
                             password: Option[String],
                             sasl: Boolean,
                             autorun: Option[String],
                             autojoin: Option[String]) {
    def blank(s: Option[String]) = s.forall(_.trim.isEmpty)
    def blank(s: String) = s.trim.isEmpty
    def valid: Boolean = !blank(name) && !blank(hostname) && !blank(nickname) &&
      !blank(altnick orElse nickname.map(_ + "_")) && port > 0 && !blank(username) && !blank(realname)

    def asData = for {
      n <- name
      host <- hostname
      nick <- nickname
      real <- realname
      user <- username
    } yield {
      Server.ServerData(id, n, host, nick, altnick.getOrElse(nick + "_"),
        port, real, user, ssl, autoconnect, password, sasl, autorun, autojoin)
    }
  }
}
class ServerSetupFragment extends Fragment with PureFragmentCompat[ServerSetupFragment.ServerEditModel] {
  import ServerSetupFragment._

  override def applyState[T](s: FragmentState[T]) = s match {
    case OnCreate(st) => s(IO(setHasOptionsMenu(true)))
    case o@OnOptionsItemSelected(st, item) => o.applyResult(IO { // intellij...
      item.getItemId match {
        case R.id.save_server =>
          if (st.valid) {
            st.asData.foreach { data =>
              if (st.id == -1)
                manager.addServer(data)
              else
                manager.updateServer(data)
            }
            getActivity.finish()
            hideIME()
          } else {
            Toast.makeText(getActivity, R.string.server_incomplete,
              Toast.LENGTH_SHORT).show()
          }
          true
        case android.R.id.home =>
          getActivity.finish()
          hideIME()
          true
        case _ => false
      }
    })
    case o@OnCreateView(st, inflater, container) => o.applyResult(IO { // intellij again
      loadModel(st)
      server_name.onTextChangeIO(s => transformState(_.copy(name = sanitize(s))))
      server_host.onTextChangeIO(s => transformState(_.copy(hostname = sanitize(s))))
      port.onTextChangeIO(s =>
        transformState(_.copy(port = Try(s.toString.trim.toInt).toOption.getOrElse(0))))
      ssl.onCheckedChange0(transformState(_.copy(ssl = ssl.isChecked)).perform())
      sasl.onCheckedChange0(transformState(_.copy(sasl = sasl.isChecked)).perform())
      autoconnect.onCheckedChange0(transformState(_.copy(autoconnect = autoconnect.isChecked)).perform())
      nickname.onTextChangeIO(s => transformState(_.copy(nickname = sanitize(s))))
      altnick.onTextChangeIO(s => transformState(_.copy(altnick = sanitize(s))))
      realname.onTextChangeIO(s => transformState(_.copy(realname = sanitize(s))))
      username.onTextChangeIO(s => transformState(_.copy(username = sanitize(s))))
      password.onTextChangeIO(s => transformState(_.copy(password = sanitize(s))))
      autojoin.onTextChangeIO(s => transformState(_.copy(autojoin = sanitize(s))))
      autorun.onTextChangeIO(s => transformState(_.copy(autorun = sanitize(s))))

      layout
    })
    case OnCreateOptionsMenu(_, menu, inflater) =>
      s(IO(inflater.inflate(R.menu.server_setup_menu, menu)))
    case SaveState(st, bundle) => s(IO {
      bundle.putSerializable(EXTRA_MODEL, st)
    })
    case x => defaultApplyState(s)
  }

  override def initialState(savedState: Option[Bundle], arguments: Option[Bundle]) = {
    val model = savedState flatMap { b =>
      b.getSerializable(EXTRA_MODEL).asInstanceOf[ServerEditModel].?
    } orElse {
      for {
        args <- arguments
        id = args.getLong(ServerSetupActivity.EXTRA_SERVER, -1)
        s <- Config.servers.now.find(_.id == id) if id != -1
      } yield {
        ServerEditModel.fromData(s.data)
      }
    }
    model getOrElse ServerEditModel.empty
  }

  val manager = IrcManager.init()

  def header =
    new TextView(getActivity, null, android.R.attr.listSeparatorTextViewStyle)

  def label = c[TableRow](w[TextView] >>=
    lpK(WRAP_CONTENT, WRAP_CONTENT)(margins(right = 12.dp)))

  lazy val layout = {
    def inputTweaks: Kestrel[EditText] = c[TableRow](kestrel { e: EditText =>
      e.setSingleLine(true)
    } >=> lp(0, WRAP_CONTENT, 1))

    c[ViewGroup](l[ScrollView](
      l[TableLayout](
        IO(header) >>= k.text("Connection Info"),
        l[TableRow](
          label >>= k.text("Name"),
          IO(server_name) >>= inputTweaks >>= k.hint("required") >>= textCapWords
        ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          label >>= k.text("Server address"),
          IO(server_host) >>= inputTweaks >>= k.hint("required") >>= textUri
        ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          label >>= k.text("Port"),
          IO(port) >>= inputTweaks >>= k.hint("Default: 6667") >>= number
        ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          w[View],
          IO(autoconnect) >>= k.text("Enable Autoconnect")
        ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          w[View],
          IO(ssl) >>= k.text("Enable SSL")
        ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
        IO(header) >>= k.text("User Info"),
        l[TableRow](
          label >>= k.text("Nickname"),
          IO(nickname) >>= inputTweaks >>= k.hint("required")
        ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          label >>= k.text("Alt. nick"),
          IO(altnick) >>= inputTweaks >>= k.hint("Default: <Nickname>_")
        ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          label >>= k.text("Real name"),
          IO(realname) >>= inputTweaks >>= k.hint("required") >>= textCapWords
        ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          w[View],
          IO(sasl) >>= k.text("SASL authentication")
        ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          label >>= k.text("Username"),
          IO(username) >>= inputTweaks >>= k.hint("required")
        ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          label >>= k.text("Password"),
          IO(password) >>= inputTweaks >>= k.hint("optional") >>= textPassword
        ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
        IO(header) >>= k.text("Session Options"),
        l[TableRow](
          label >>= k.text("Auto join"),
          IO(autojoin) >>= inputTweaks >>= k.hint("#chan1 key;#chan2")
        ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          label >>= k.text("Auto run"),
          IO(autorun) >>= inputTweaks >>= k.hint("m pfn hi there;")
        ) >>= lp(MATCH_PARENT, WRAP_CONTENT)
      ) >>= lpK(MATCH_PARENT, MATCH_PARENT)(margins(all = 8 dp)) >>=
        kestrel(_.setColumnStretchable(1, true)) >>= padding(all = 16.dp)
    ) >>= lp(MATCH_PARENT, MATCH_PARENT)).perform()
  }

  lazy val server_name = new EditText(getActivity)
  lazy val server_host = new EditText(getActivity)
  lazy val port = new EditText(getActivity)
  lazy val ssl = checkbox
  lazy val autoconnect = checkbox
  lazy val nickname = new EditText(getActivity)
  lazy val altnick = new EditText(getActivity)
  lazy val realname = new EditText(getActivity)
  lazy val username = new EditText(getActivity)
  lazy val password = new EditText(getActivity)
  lazy val autojoin = new EditText(getActivity)
  lazy val autorun = new EditText(getActivity)
  lazy val sasl = checkbox

  def sanitize(s: CharSequence) = s.toString.?.map(_.trim).filter(_.nonEmpty)
  def loadModel(s: ServerEditModel) = {
    server_name.setText(s.name.getOrElse(""))
    server_host.setText(s.hostname.getOrElse(""))
    port.setText("" + s.port)
    ssl.setChecked(s.ssl)
    sasl.setChecked(s.sasl)
    autoconnect.setChecked(s.autoconnect)
    nickname.setText(s.nickname.getOrElse(""))
    altnick.setText(s.altnick.getOrElse(""))
    realname.setText(s.realname.getOrElse(""))
    username.setText(s.username.getOrElse(""))
    password.setText(s.password.getOrElse(""))
    autojoin.setText(s.autojoin.getOrElse(""))
    autorun.setText(s.autorun.getOrElse(""))
  }
}
