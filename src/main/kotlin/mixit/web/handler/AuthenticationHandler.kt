package mixit.web.handler

import mixit.MixitProperties
import mixit.model.Role
import mixit.model.User
import mixit.repository.UserRepository
import mixit.util.EmailService
import mixit.util.locale
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyExtractors
import org.springframework.web.reactive.function.BodyExtractors.toFormData
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.ServerResponse.*
import org.springframework.web.server.WebSession
import reactor.core.publisher.Mono
import java.net.URI
import java.time.LocalDateTime
import java.util.*


@Component
class AuthenticationHandler(private val userRepository: UserRepository,
                            private val properties: MixitProperties,
                            private val emailService: EmailService) {


    private val logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * Display a view with a form to send the email of the user
     */
    fun loginView(req: ServerRequest) = ok().render("login")

    /**
     * Action when user wants to sign in
     */
    fun login(req: ServerRequest): Mono<ServerResponse> = req.body(toFormData()).flatMap { data ->
        searchUserAndSendToken(data.toSingleValueMap()["email"], req.locale())
    }

    /**
     * Action when user wants to log out
     */
    fun logout(req: ServerRequest): Mono<ServerResponse> = req.session().flatMap { session ->
        session.attributes.remove("email")
        session.attributes.remove("token")
        temporaryRedirect(URI("${properties.baseUri}/")).build()
    }

    private fun renderError(error: String?): Mono<ServerResponse> = ok().render("login-error", mapOf(Pair("description", error)))

    /**
     * Email is required for the sign in process. If email is not in our database, we ask to the visitor to create
     * a new account. If the account already exists we send him a token by email
     */
    private fun searchUserAndSendToken(email: String?, locale: Locale): Mono<ServerResponse> {
        // Email is required
        if (email == null) return renderError("login.error.text")

        val context = mapOf(Pair("email", email))

        // We need to know if user exists or not
        return userRepository.findByEmail(email)
                .flatMap { user ->
                    // if user exists we send a token by email
                    sendUserToken(email, user, locale)
                            .flatMap { user ->
                                // if token is sent we call the the screen where user can type this token
                                ok().render("login-confirmation", context)
                            }
                            // if not this is an error
                            .switchIfEmpty(renderError("login.error.sendtoken.text"))
                }
                // if user is not found we ask him if he wants to create a new account
                .switchIfEmpty(ServerResponse.ok().render("login-creation", context))
    }

    /**
     * Action to create a new account. Email, firstname, lastname are required in the form sent by the user
     */
    fun signUp(req: ServerRequest): Mono<ServerResponse> = req.body(BodyExtractors.toFormData()).flatMap {
        val formData = it.toSingleValueMap()
        val user = User(
                login = formData["email"]!!,
                firstname = formData["firstname"]!!,
                lastname = formData["lastname"]!!,
                email = formData["email"]!!,
                photoUrl = "/images/png/mxt-icon--default-avatar.png",
                role = Role.USER
        )

        userRepository.findByEmail(user.email!!)
                // Email is unique and if an email is found we return an error
                .flatMap { usr ->
                    renderError("login.error.uniqueemail.text")
                }
                .switchIfEmpty(
                        userRepository
                                .save(user)
                                // if user is created we send him a token by email
                                .flatMap { usr ->
                                    searchUserAndSendToken(usr.email, req.locale())
                                }
                                // otherwise we display an error
                                .switchIfEmpty(renderError("login.error.creation.text"))
                )
    }

    /**
     * Action when user wants to send his token to open a session. This token is valid only for a limited time
     */
    fun signInByForm(req: ServerRequest): Mono<ServerResponse> = req.body(toFormData()).flatMap { data ->
        val formData = data.toSingleValueMap()
        val email = formData["email"]
        val token = formData["token"]

        req.session().flatMap { session ->
            signInProcess(email, token, session)
        }
    }

    /**
     * Action when user wants to send his token to open a session. This token is valid only for a limited time
     */
    fun signIn(req: ServerRequest): Mono<ServerResponse> = req.session().flatMap { session ->
        val email = req.pathVariable("user")
        val token = req.pathVariable("token")

        signInProcess(email, token, session)
    }

    private fun signInProcess(email: String?, token: String?, session: WebSession): Mono<ServerResponse>{
        // If email or token are null we can't open a session
        if (email == null || token == null) {
            return renderError("login.error.required.text")
        }

        return userRepository.findByEmail(email!!)
                // User must exist at this point
                .flatMap { user ->
                    if (token.trim().equals(user.token)) {
                        if (user.tokenExpiration.isBefore(LocalDateTime.now())) {
                            // token has to be valid
                            renderError("login.error.token.text")
                        } else {
                            session.attributes["username"] = email
                            session.attributes["token"] = token
                            seeOther(URI("${properties.baseUri}/")).build()
                        }
                    }
                    else {
                        renderError("login.error.badtoken.text")
                    }

                }
                .switchIfEmpty(renderError("login.error.bademail.text"))
    }

    /**
     * Sends an email with a token to the user. We don't need validation of the email adress. If he receives
     * the email it's OK. If he retries a login a new token is sent
     */
    private fun sendUserToken(email: String, user: User, locale: Locale): Mono<User> {
        user.token = UUID.randomUUID().toString()
        user.tokenExpiration = LocalDateTime.now().plusHours(12)

        try {
            logger.info("A token was sent to ${email}")
            emailService.sendUserTokenEmail(user, locale)
            return userRepository.save(user)
        } catch (e: RuntimeException) {
            return Mono.empty()
        }
    }
}
